package com.cyeam.cnote

import android.app.job.JobParameters
import android.app.job.JobService
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

open class SharedSyncService:JobService() {
    private var job:Job?=null
    override fun onStartJob(params:JobParameters):Boolean {
        job=(application as CNoteApp).scope.launch {
            try { val retry=(application as CNoteApp).shared.sync();jobFinished(params,retry) }
            catch(_:Exception){jobFinished(params,true)}
        }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean { job?.cancel();return true }
}
