package com.yoonjaepark.flutter_naver_login
import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.AsyncTask
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.nhn.android.naverlogin.OAuthLogin
import com.nhn.android.naverlogin.OAuthLoginHandler
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import io.flutter.plugin.common.PluginRegistry.Registrar
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutionException

class FlutterNaverLoginPlugin : MethodCallHandler {
    /** Plugin registration.  */

    private val METHOD_LOG_IN = "logIn"
    private val METHOD_LOG_OUT = "logOut"
    private val METHOD_GET_ACCOUNT = "getCurrentAcount"

    private val METHOD_GET_TOKEN = "getCurrentAccessToken"

    /**
     * 네이버 개발자 등록한 client 정보를 넣어준다.
     */
    private var OAUTH_CLIENT_ID = "OAUTH_CLIENT_ID"
    private var OAUTH_CLIENT_SECRET = "OAUTH_CLIENT_SECRET"
    private var OAUTH_CLIENT_NAME = "OAUTH_CLIENT_NAME"

    private val mOAuthLoginInstance: OAuthLogin
    private val currentActivity: Activity
    private val mContext: Context

    private var ai: ApplicationInfo? = null
    private var e: String? = null
    private var bundle: Bundle? = null
    companion object {
        @JvmStatic
        fun registerWith(registrar: Registrar) {
            val channel = MethodChannel(registrar.messenger(), "flutter_naver_login")
            channel.setMethodCallHandler(FlutterNaverLoginPlugin(registrar))
        }
    }

    constructor(registrar: PluginRegistry.Registrar) {
        currentActivity = registrar.activity()
        mOAuthLoginInstance = OAuthLogin.getInstance()
        mOAuthLoginInstance.showDevelopersLog(true)
        mContext = registrar.context()

        try {
            e = mContext.packageName;
            Log.d("packageName", e);

            ai = mContext
                    .packageManager
                    .getApplicationInfo(e, PackageManager.GET_META_DATA)

            bundle = ai?.metaData;
            if(bundle != null) {
                OAUTH_CLIENT_ID = bundle?.getString("com.naver.sdk.clientId").toString();
                OAUTH_CLIENT_SECRET = bundle?.getString("com.naver.sdk.clientSecret").toString();
                OAUTH_CLIENT_NAME = bundle?.getString("com.naver.sdk.clientName").toString();

                mOAuthLoginInstance.showDevelopersLog(true);
                mOAuthLoginInstance.init(this.mContext, OAUTH_CLIENT_ID, OAUTH_CLIENT_SECRET, OAUTH_CLIENT_NAME);
            }

        } catch (e: Exception) {
            e.printStackTrace();
        }
    }


    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            METHOD_LOG_IN -> this.login(result)
            METHOD_LOG_OUT -> this.logout(result)
            METHOD_GET_TOKEN -> {
                result.success(object : HashMap<String, String>() {
                    init {
                        put("status", "getToken")
                        put("accessToken", mOAuthLoginInstance.getAccessToken(mContext))
                        put("expiresAt", mOAuthLoginInstance.getExpiresAt(mContext).toString())
                        put("tokenType", mOAuthLoginInstance.getTokenType(mContext))
                    }
                })
            }
            METHOD_GET_ACCOUNT -> this.currentAccount(result)
            else -> result.notImplemented()
        }
    }

    fun currentAccount(result: Result) {
        val accessToken = mOAuthLoginInstance.getAccessToken(mContext)

        val task = ProfileTask()
        try {
            val res = task.execute(accessToken).get()
            val obj = JSONObject(res)
            var resultProfile = jsonToMap(obj.getString("response"))
            resultProfile["status"] = "loggedIn"
            result.success(resultProfile)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        } catch (e: ExecutionException) {
            e.printStackTrace()
        } catch (e: JSONException) {
            e.printStackTrace()
        }
    }

    fun login(result: Result) {
        val mOAuthLoginHandler = object : OAuthLoginHandler() {
            override fun run(success: Boolean) {
                if (success) {
                    currentAccount(result)
                } else {
                    val errorCode = mOAuthLoginInstance.getLastErrorCode(mContext).code
                    val errorDesc = mOAuthLoginInstance.getLastErrorDesc(mContext)
                    result.success(object : HashMap<String, String>() {
                        init {
                            put("status", "error")
                            put("errorMessage", "errorCode:$errorCode, errorDesc:$errorDesc")
                        }
                    })
                }
            }
        }
        mOAuthLoginInstance.startOauthLoginActivity(currentActivity, mOAuthLoginHandler)
    }

    fun logout(result: Result) {
        DeleteTokenTask().execute(result)
    }

    private inner class DeleteTokenTask : AsyncTask<Result, Void, Void>() {
        override fun doInBackground(vararg params: Result?): Void? {
            mOAuthLoginInstance.logout(mContext)
//            var LoginInstanceResult: Result? = params[0];
//            val isSuccessDeleteToken = mOAuthLoginInstance.logoutAndDeleteToken(mContext)

//            if (isSuccessDeleteToken) {
//                LoginInstanceResult?.success(object : HashMap<String, Any>() {
//                    init {
//                        put("status", "cancelledByUser")
//                        put("isLogin", false)
//                    }
//                })
//            } else {
//                // 서버에서 token 삭제에 실패했어도 클라이언트에 있는 token 은 삭제되어 로그아웃된 상태이다
//                // 실패했어도 클라이언트 상에 token 정보가 없기 때문에 추가적으로 해줄 수 있는 것은 없음
//                val errorCode = mOAuthLoginInstance.getLastErrorCode(mContext).code
//                val errorDesc = mOAuthLoginInstance.getLastErrorDesc(mContext)
//                LoginInstanceResult?.success(object : HashMap<String, String>() {
//                    init {
//                        put("status", "error")
//                        put("errorMessage", "errorCode:$errorCode, errorDesc:$errorDesc")
//                    }
//                })
//            }

            return null
        }

        override fun onPostExecute(v: Void?) {
        }
    }

    internal inner class ProfileTask : AsyncTask<String, Void, String>() {
        var result: String = "";
        override fun doInBackground(vararg arg: String): String {
            val token = arg[0]// 네이버 로그인 접근 토큰;
            val header = "Bearer $token" // Bearer 다음에 공백 추가
            try {
                val apiURL = "https://openapi.naver.com/v1/nid/me"
                val url = URL(apiURL)
                val con = url.openConnection() as HttpURLConnection
                con.requestMethod = "GET"
                con.setRequestProperty("Authorization", header)
                val responseCode = con.responseCode
                val br: BufferedReader
                if (responseCode == 200) { // 정상 호출
                    br = BufferedReader(InputStreamReader(con.inputStream))
                } else {  // 에러 발생
                    br = BufferedReader(InputStreamReader(con.errorStream))
                }
                val response = StringBuffer()
                val allText = br.use(BufferedReader::readText)
                result = allText
                br.close()
                println(response.toString())
            } catch (e: Exception) {
                println(e)
            }

            //result 값은 JSONObject 형태로 넘어옵니다.
            return result
        }

        override fun onPostExecute(s: String) {

        }
    }

    @Throws(JSONException::class)
    fun jsonToMap(t: String): HashMap<String, String> {

        val map = HashMap<String, String>()
        val jObject = JSONObject(t)
        val keys = jObject.keys()

        while (keys.hasNext()) {
            val key = keys.next() as String
            val value = jObject.getString(key)
            map[key] = value
        }
        return map
    }
}
