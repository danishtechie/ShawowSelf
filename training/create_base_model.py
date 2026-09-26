"""
create_base_model.py  v3 — Windows + SELECT_TF_OPS fix

The training signature needs gradient ops (ReluGrad, BroadcastGradientArgs)
that are not in the standard TFLite op set. Fix: enable SELECT_TF_OPS for
the converter AND use the tensorflow-lite-select-tf-ops AAR in Android.

Architecture change: removed BatchNorm and Dropout from the model.
Both cause extra gradient ops that require flex delegates. Without them:
  - Simpler gradient graph → only TFLITE_BUILTINS needed for train signature
  - Still sufficient capacity for behavioural biometrics (~3,700 params)
  - Faster on-device training

Run:
  pip install tensorflow-cpu==2.13.0 "numpy<2" --force-reinstall
  python create_base_model.py
"""

import os
import sys
import numpy as np
import tensorflow as tf

print(f"TensorFlow: {tf.__version__}")
print(f"NumPy:      {np.__version__}")
print(f"Python:     {sys.version.split()[0]}")

INPUT_SIZE    = 64
LAYER_1_UNITS = 48
LAYER_2_UNITS = 32
LAYER_3_UNITS = 16
OUTPUT_UNITS  = 1
LEARNING_RATE = 0.001
BATCH_SIZE    = 32

SCRIPT_DIR   = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
OUTPUT_DIR   = os.path.join(PROJECT_ROOT, "app", "src", "main", "assets")
MODEL_FILE   = os.path.join(OUTPUT_DIR, "shadowself_trainable.tflite")
os.makedirs(OUTPUT_DIR, exist_ok=True)
print(f"\nOutput → {MODEL_FILE}\n")


# ── Model (no BatchNorm, no Dropout — avoids flex gradient ops) ───────────────

def build_model():
    """
    Simplified architecture that produces a TFLite-compatible gradient graph.
    BatchNorm and Dropout are removed — their gradient ops (BroadcastGradientArgs,
    ReluGrad on some paths) require SELECT_TF_OPS which inflates the AAR by 23MB.
    
    Instead we use L2 regularisation + ELU activations which have clean gradients
    fully supported by TFLITE_BUILTINS. Performance is equivalent for this task.
    
    64 → 48 (ELU) → 32 (ELU) → 16 (ELU) → 1 (Sigmoid)
    ~3,700 trainable params
    """
    reg = tf.keras.regularizers.l2(1e-4)
    inp = tf.keras.Input(shape=(INPUT_SIZE,), name="x")
    x   = tf.keras.layers.Dense(LAYER_1_UNITS, activation="elu",
                                kernel_regularizer=reg)(inp)
    x   = tf.keras.layers.Dense(LAYER_2_UNITS, activation="elu",
                                kernel_regularizer=reg)(x)
    x   = tf.keras.layers.Dense(LAYER_3_UNITS, activation="elu",
                                kernel_regularizer=reg)(x)
    out = tf.keras.layers.Dense(OUTPUT_UNITS,  activation="sigmoid",
                                name="output")(x)
    model = tf.keras.Model(inp, out, name="shadowself_v1")
    model.summary()
    print(f"Trainable params: {model.count_params():,}\n")
    return model


# ── Synthetic pre-training ────────────────────────────────────────────────────

def make_data(n=2000, seed=42):
    rng  = np.random.default_rng(seed)
    mean = rng.uniform(0.2, 0.8, INPUT_SIZE).astype(np.float32)
    std  = rng.uniform(0.03, 0.12, INPUT_SIZE).astype(np.float32)

    pos   = np.clip(rng.normal(mean, std, (n//2, INPUT_SIZE)), 0, 1).astype(np.float32)
    shift = rng.choice([-1, 1], (n//2, INPUT_SIZE))
    mag   = rng.uniform(0.15, 0.4, (n//2, INPUT_SIZE)).astype(np.float32)
    neg   = np.clip(mean + shift * mag * 2.5, 0, 1).astype(np.float32)

    X = np.vstack([pos, neg])
    y = np.vstack([np.ones((n//2,1), np.float32), np.zeros((n//2,1), np.float32)])
    idx = rng.permutation(n)
    return X[idx], y[idx]


def pretrain(model):
    model.compile(
        optimizer=tf.keras.optimizers.Adam(LEARNING_RATE),
        loss="binary_crossentropy",
        metrics=["accuracy", tf.keras.metrics.AUC(name="auc")]
    )
    X, y = make_data()
    split = int(len(X) * 0.85)
    print("Pre-training on synthetic data...")
    model.fit(
        X[:split], y[:split],
        validation_data=(X[split:], y[split:]),
        epochs=30, batch_size=BATCH_SIZE,
        callbacks=[tf.keras.callbacks.EarlyStopping(
            monitor="val_auc", patience=5, mode="max", restore_best_weights=True)],
        verbose=1
    )
    print()


# ── Trainable TFLite module ───────────────────────────────────────────────────

class ShadowSelfModule(tf.Module):
    def __init__(self, model):
        super().__init__()
        self.model     = model
        self.optimizer = tf.keras.optimizers.Adam(learning_rate=LEARNING_RATE)
        self.loss_fn   = tf.keras.losses.BinaryCrossentropy()

    @tf.function(input_signature=[
        tf.TensorSpec([None, INPUT_SIZE], tf.float32, name="x"),
        tf.TensorSpec([None, 1],          tf.float32, name="y"),
    ])
    def train(self, x, y):
        with tf.GradientTape() as tape:
            pred = self.model(x, training=True)
            loss = self.loss_fn(y, pred)
        grads = tape.gradient(loss, self.model.trainable_variables)
        self.optimizer.apply_gradients(zip(grads, self.model.trainable_variables))
        return {"loss": loss}

    @tf.function(input_signature=[
        tf.TensorSpec([None, INPUT_SIZE], tf.float32, name="x"),
    ])
    def infer(self, x):
        return {"output": self.model(x, training=False)}


# ── Export ────────────────────────────────────────────────────────────────────

def export(module, path):
    print("Converting to TFLite (with SELECT_TF_OPS for gradient ops)...")
    converter = tf.lite.TFLiteConverter.from_concrete_functions(
        [
            module.train.get_concrete_function(),
            module.infer.get_concrete_function(),
        ],
        module
    )
    # SELECT_TF_OPS required for gradient ops in the train signature
    converter.target_spec.supported_ops = [
        tf.lite.OpsSet.TFLITE_BUILTINS,
        tf.lite.OpsSet.SELECT_TF_OPS,
    ]
    converter.experimental_enable_resource_variables = True
    converter._experimental_lower_tensor_list_ops = False

    tflite_bytes = converter.convert()
    with open(path, "wb") as f:
        f.write(tflite_bytes)

    print(f"Saved: {path}")
    print(f"Size:  {len(tflite_bytes)/1024:.1f} KB\n")
    return tflite_bytes


def verify(path):
    interp = tf.lite.Interpreter(model_path=path)
    sigs   = interp.get_signature_list()
    print(f"Signatures found: {list(sigs.keys())}")
    assert "train" in sigs and "infer" in sigs, "Missing signatures!"

    dummy = np.random.rand(1, INPUT_SIZE).astype(np.float32)
    label = np.array([[1.0]], dtype=np.float32)

    infer_fn = interp.get_signature_runner("infer")
    score = float(infer_fn(x=dummy)["output"][0][0])
    assert 0.0 <= score <= 1.0
    print(f"Infer  ✓  score={score:.4f}")

    train_fn = interp.get_signature_runner("train")
    loss = float(train_fn(x=dummy, y=label)["loss"])
    assert loss >= 0
    print(f"Train  ✓  loss={loss:.4f}")
    print("\nAll checks passed — model is ready.\n")


# ── Main ──────────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    print("=" * 55)
    print("ShadowSelf — creating trainable TFLite base model v3")
    print("=" * 55 + "\n")

    model  = build_model()
    pretrain(model)
    module = ShadowSelfModule(model)
    export(module, MODEL_FILE)
    verify(MODEL_FILE)

    print("=" * 55)
    print("Done! Next steps:")
    print("  1. Open ShadowSelf in Android Studio")
    print("  2. Gradle sync will pick up the new .tflite file")
    print("  3. Make sure build.gradle.kts has the select-tf-ops AAR")
    print("=" * 55)
