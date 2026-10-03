package io.tezra.fermix.chat

/**
 * A test that needs a phone that folds. CI's `ui` job leaves it out on its phone profile, through the
 * runner's `notAnnotation`, so that a phone's report holds no skip that reads as a failure, and on its
 * folding profile requires the fold, so that a device that cannot fold fails it; on any other device that
 * cannot fold, its assumption skips it.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class FoldingPhone
