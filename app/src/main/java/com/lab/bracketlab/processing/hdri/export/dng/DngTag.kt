package com.lab.bracketlab.processing.hdri.export.dng

internal object DngTag {
    const val NEW_SUBFILE_TYPE = 254
    const val IMAGE_WIDTH = 256
    const val IMAGE_LENGTH = 257
    const val BITS_PER_SAMPLE = 258
    const val COMPRESSION = 259
    const val PHOTOMETRIC_INTERPRETATION = 262
    const val IMAGE_DESCRIPTION = 270
    const val MAKE = 271
    const val MODEL = 272
    const val STRIP_OFFSETS = 273
    const val ORIENTATION = 274
    const val SAMPLES_PER_PIXEL = 277
    const val ROWS_PER_STRIP = 278
    const val STRIP_BYTE_COUNTS = 279
    const val PLANAR_CONFIGURATION = 284
    const val SOFTWARE = 305
    const val DATE_TIME = 306
    const val SAMPLE_FORMAT = 339
    const val DNG_VERSION = 50706
    const val DNG_BACKWARD_VERSION = 50707
    const val UNIQUE_CAMERA_MODEL = 50708
    const val BLACK_LEVEL_REPEAT_DIM = 50713
    const val BLACK_LEVEL = 50714
    const val WHITE_LEVEL = 50717
    const val DEFAULT_SCALE = 50718
    const val DEFAULT_CROP_ORIGIN = 50719
    const val DEFAULT_CROP_SIZE = 50720
    const val COLOR_MATRIX_1 = 50721
    const val COLOR_MATRIX_2 = 50722
    const val CAMERA_CALIBRATION_1 = 50723
    const val CAMERA_CALIBRATION_2 = 50724
    const val AS_SHOT_NEUTRAL = 50728
    const val BASELINE_EXPOSURE = 50730
    const val CALIBRATION_ILLUMINANT_1 = 50778
    const val CALIBRATION_ILLUMINANT_2 = 50779
    const val FORWARD_MATRIX_1 = 50964
    const val FORWARD_MATRIX_2 = 50965
    const val DEFAULT_BLACK_RENDER = 51110

    const val COMPRESSION_NONE = 1
    const val PHOTOMETRIC_LINEAR_RAW = 34892
    const val PLANAR_CHUNKY = 1
    const val SAMPLE_FORMAT_IEEE_FLOAT = 3
    const val SUBFILE_TYPE_MAIN_IMAGE = 0
}
