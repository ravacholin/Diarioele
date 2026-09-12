package com.capo.diarioclase.recording
enum class PreflightReason { INSUFFICIENT_SPACE,MICROPHONE_PERMISSION,CRITICAL_BATTERY }
sealed interface PreflightResult { data object Ready:PreflightResult;data class Blocked(val reason:PreflightReason):PreflightResult }
class PreflightChecker {
 fun check(freeBytes:Long,batteryPercent:Int,micGranted:Boolean):PreflightResult=when{
  !micGranted->PreflightResult.Blocked(PreflightReason.MICROPHONE_PERMISSION)
  freeBytes<1_000_000_000L->PreflightResult.Blocked(PreflightReason.INSUFFICIENT_SPACE)
  batteryPercent<10->PreflightResult.Blocked(PreflightReason.CRITICAL_BATTERY)
  else->PreflightResult.Ready
 }
}

