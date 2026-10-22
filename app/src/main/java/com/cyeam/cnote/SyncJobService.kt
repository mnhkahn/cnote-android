package com.cyeam.cnote

import android.app.job.JobParameters
import android.app.job.JobService
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SyncJobService:JobService() {
    private var job:Job?=null
    override fun onStartJob(params:JobParameters):Boolean {
        val app=application as CNoteApp
        job=app.scope.launch { val retry=app.repository.sync(false);jobFinished(params,retry) }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean { job?.cancel();return true }
}
