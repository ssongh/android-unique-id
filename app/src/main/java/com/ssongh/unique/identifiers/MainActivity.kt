package com.ssongh.unique.identifiers

import android.annotation.SuppressLint
import android.media.MediaDrm
import android.media.UnsupportedSchemeException
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import com.google.firebase.installations.FirebaseInstallations
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.util.*
import kotlin.coroutines.CoroutineContext

private const val TAG = "unique-id"

class MainActivity : AppCompatActivity(), CoroutineScope {

    private lateinit var job: Job
    override val coroutineContext: CoroutineContext
        get() = job + Dispatchers.Main
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("CoroutineException", throwable.toString())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        job = Job()
        MobileAds.initialize(this) {}


        // GUID
        Log.d(TAG, "GUID : ${requestGUID()}")
        Log.d(TAG, "==================================================")

        // 인스턴스 ID
        requestInstanceID()

        launch(exceptionHandler) {
            // 인스턴스 ID
            val defInstanceID = async(Dispatchers.Default) {
                callBackInstanceID()
            }
            Log.d(TAG, "인스턴스 ID Coroutine : ${defInstanceID.await()}")
            Log.d(TAG, "==================================================")

            // 광고 ID
            val defAdvertisingID = async(Dispatchers.Default) {
                requestAdvertisingInfo()
            }
            Log.d(TAG, "AD ID Coroutine : ${defAdvertisingID.await()}")
            Log.d(TAG, "==================================================")

            // Widevine ID
            val widevineUUID = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)
            val wvDrm = try {
                MediaDrm(widevineUUID)
            } catch (e: UnsupportedSchemeException) {
                null
            }
            Log.d(TAG, "Widevine level : ${wvDrm?.getPropertyString("securityLevel")}")
            val defWidevineID = async(Dispatchers.Default) {
                requestWidevineID(wvDrm)
            }
            Log.d(TAG, "Widevine ID Coroutine : ${defWidevineID.await()}")
            Log.d(TAG, "==================================================")
            wvDrm?.close()
        }

        // SSAID
        Log.d(TAG, "SSAID : ${requestSSAID()}")
        Log.d(TAG, "==================================================")
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    /**
     * GUID
     * 범위 : 단일 앱, 앱 그룹(직접 구현 필요)
     * 수명 : 앱 삭제 및 재설치, 앱 데이터 삭제
     */
    private fun requestGUID() = UUID.randomUUID().toString()


    /**
     * 인스턴스 ID
     * 범위 : 단일 앱
     * 수명 : 앱 삭제 및 재설치, 앱 데이터 삭제
     */
    private fun requestInstanceID() {
        FirebaseInstallations.getInstance().id.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w(TAG, "getInstanceId failed", task.exception)
                return@addOnCompleteListener
            }
            Log.d(TAG, "인스턴스 ID : ${task.result}")
            Log.d(TAG, "==================================================")
        }
    }

    private suspend fun callBackInstanceID(): String? {
        var instanceId: String? = null
        FirebaseInstallations.getInstance().id.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w(TAG, "getInstanceId failed", task.exception)
                return@addOnCompleteListener
            }
            instanceId = task.result
        }.await()

        return instanceId
    }


    /**
     * 광고 ID
     * 범위 : 디바이스
     * 수명 : 디바이스 초기화, 광고 ID 초기화(구글 설정>광고>광고 ID 재설정)
     */
    private suspend fun requestAdvertisingInfo(): String? =
        coroutineScope {
            val advertisingId = async(Dispatchers.IO) {
                val advertisingIdInfo = AdvertisingIdClient.getAdvertisingIdInfo(applicationContext)
                val advertisingId: String? = advertisingIdInfo?.run {
                    if (!isLimitAdTrackingEnabled) {
                        id
                    } else {
                        null
                    }
                }
                advertisingId
            }
            advertisingId.await()
        }


    /**
     * SSAID
     * 범위 : 앱 그룹 (안드로이드 8.0 이상, 동일한 서명 키로 서명된 경우)
     *       디바이스 (안드로이드 8.0 미만)
     * 수명 : 디바이스 초기화
     */
    @SuppressLint("HardwareIds")
    private fun requestSSAID() =
        Settings.Secure.getString(applicationContext.contentResolver, Settings.Secure.ANDROID_ID)


    /**
     * Widevine ID (DRM API)
     * 범위 : 앱 패키지 이름 (안드로이드 8.0 이상), 디바이스 (안드로이드 8.0 미만)
     * 수명 : L1 -> 변경 방법 없음, L3 -> 디바이스 초기화
     * 특징 : Widevine DRM 모듈이 없는 디바이스에서는 사용불가
     *       안드로이드 4.3 미만은 사용불가
     */
    private suspend fun requestWidevineID(wvDrm: MediaDrm?): String? =
        coroutineScope {
            val widevineID = async() {
                var encodedWidevineId: String? = null
                wvDrm?.run {
                    val widevineId = getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)

                    encodedWidevineId =
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            Base64.getEncoder().encodeToString(widevineId).trim()
                        } else {
                            android.util.Base64.encodeToString(
                                widevineId,
                                android.util.Base64.DEFAULT
                            )
                        }
                    Log.d(TAG, "Widevine ID : $encodedWidevineId")
                }
                encodedWidevineId
            }
            widevineID.await()
        }
}