package com.shadowself.training.model

/**
 * ModelArchitecture
 *
 * Defines the neural network structure for on-device training with TFLite.
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  Input layer      [1 × 64]  — normalised feature vector                │
 * │                                                                         │
 * │  Dense 64 → 48   ReLU   + BatchNorm + Dropout(0.3)                     │
 * │  Dense 48 → 32   ReLU   + BatchNorm + Dropout(0.2)                     │
 * │  Dense 32 → 16   ReLU                                                  │
 * │  Dense 16 →  1   Sigmoid — identity confidence [0.0 – 1.0]            │
 * │                                                                         │
 * │  Loss:      BinaryCrossentropy (with label smoothing 0.05)              │
 * │  Optimizer: Adam (lr = 0.001, β₁ = 0.9, β₂ = 0.999)                  │
 * │  Metrics:   Accuracy, AUC, Precision, Recall                           │
 * │                                                                         │
 * │  Total trainable parameters: ~5,600 — deliberately small for           │
 * │  on-device training speed (< 10 seconds on mid-range Android)           │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * Why this architecture:
 *   - 64 → 48 → 32 → 16 → 1 forms a "funnel" that forces progressive feature
 *     compression, creating an increasingly abstract representation of "owner-ness".
 *   - BatchNorm after the two larger layers stabilises training on small datasets
 *     (100–500 samples) where gradient variance is high.
 *   - Dropout prevents overfitting to the owner's data — critical because
 *     the owner's behaviour shifts naturally (tired typing, walking differently).
 *   - Label smoothing (0.05) prevents overconfident predictions, which would
 *     make the adaptive threshold unable to self-calibrate.
 *   - ~5,600 parameters can be trained in one pass on ~500 samples, which is
 *     important since TFLite Model Maker runs synchronously on the device.
 *
 * On fine-tuning:
 *   When false alarm feedback triggers retraining, only the final two layers
 *   (Dense 16→1 and Dense 32→16) are unfrozen. The earlier layers retain
 *   their feature extraction knowledge. This is transfer learning within
 *   the same model — faster convergence on fewer new samples.
 */
object ModelArchitecture {

    // Layer dimensions — change these if you tune the architecture
    const val INPUT_SIZE    = 64
    const val LAYER_1_UNITS = 48
    const val LAYER_2_UNITS = 32
    const val LAYER_3_UNITS = 16
    const val OUTPUT_UNITS  = 1

    // Training hyperparameters
    const val LEARNING_RATE       = 0.001f
    const val DROPOUT_RATE_1      = 0.30f
    const val DROPOUT_RATE_2      = 0.20f
    const val LABEL_SMOOTHING     = 0.05f
    const val BATCH_SIZE          = 32
    const val EPOCHS_FULL         = 50
    const val EPOCHS_FINE_TUNE    = 15        // For feedback-triggered retraining
    const val EARLY_STOPPING_PATIENCE = 8    // Stop if val_loss doesn't improve

    // Validation split (held out from training, never used for gradient updates)
    const val VALIDATION_SPLIT    = 0.15f

    // Output file location (assets dir at install, internal storage for updates)
    const val MODEL_FILENAME      = "shadowself_model.tflite"
    const val MODEL_VERSION_KEY   = "model_version"

    /**
     * Estimated parameter count (for documentation/debugging):
     *   Dense 64→48:   64*48 + 48  = 3,120
     *   Dense 48→32:   48*32 + 32  = 1,568
     *   Dense 32→16:   32*16 + 16  =   528
     *   Dense 16→1:    16*1  +  1  =    17
     *   BatchNorm ×2:  ≈ 192 (scale + offset + mean + var × 2 layers)
     *   Total:         ≈ 5,425 parameters
     */
    val estimatedParamCount: Int get() =
        (64 * 48 + 48) + (48 * 32 + 32) + (32 * 16 + 16) + (16 * 1 + 1)
}
