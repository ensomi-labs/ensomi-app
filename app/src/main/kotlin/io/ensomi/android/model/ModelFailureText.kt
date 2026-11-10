package io.ensomi.android.model

import android.content.res.Resources
import io.ensomi.android.R

/** Resolve at display time so a retained failure follows changes to the app language. */
fun ModelFailure.description(resources: Resources): String = with(resources) {
    when (val failure = this@description) {
        is ModelFailure.AtStage -> getString(failure.phase.failureLabel, failure.failure.description(this))
        is ModelFailure.Stage -> getString(failure.phase.failureLabel, getString(R.string.model_failure_unexpected))
        is ModelFailure.Network -> getString(R.string.model_failure_network)
        is ModelFailure.Http -> getString(R.string.model_failure_http, failure.code)
        is ModelFailure.Refused -> getString(R.string.model_failure_refused)
        is ModelFailure.Mismatch -> getString(R.string.model_failure_mismatch, failure.path)
        is ModelFailure.Storage -> getString(R.string.model_failure_storage)
        is ModelFailure.NoSpace -> getString(R.string.model_no_space, megabytes(failure.neededBytes), megabytes(failure.freeBytes))
        is ModelFailure.NotPinned -> getString(R.string.model_failure_not_pinned, ModelSource.PINNED_REVISION)
        ModelFailure.NoManifest -> getString(R.string.model_failure_no_manifest)
        is ModelFailure.Missing -> getString(R.string.model_failure_missing, failure.path)
        is ModelFailure.Unreadable -> getString(R.string.model_failure_unreadable)
    }
}

private val InstallPhase.failureLabel: Int
    get() = when (this) {
        InstallPhase.STAGING -> R.string.model_failure_staging
        InstallPhase.VERIFYING -> R.string.model_failure_verifying
        InstallPhase.COMMITTING -> R.string.model_failure_committing
    }
