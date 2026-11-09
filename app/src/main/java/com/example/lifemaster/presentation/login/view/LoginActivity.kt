package com.example.lifemaster.presentation.login.view

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.example.lifemaster.databinding.ActivityLoginBinding
import com.example.lifemaster.presentation.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import androidx.navigation.fragment.NavHostFragment
import com.example.lifemaster.R
import com.example.lifemaster.SubscriptionHelper
import com.example.lifemaster.network.NetworkService

import com.example.lifemaster.network.TokenManager
import com.example.lifemaster.presentation.total.mypage.model.MeResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class LoginActivity : AppCompatActivity() {

    @Inject lateinit var tokenManager: TokenManager
    @Inject lateinit var networkService: NetworkService
    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

        // 로그인 상태 유지 확인: 저장된 토큰이 있고, 딥링크(콜백)로 들어온 것이 아닐 때 메인으로 이동하기 전 갱신
        val token = tokenManager.getBearerToken()
        if (!token.isNullOrBlank() && intent?.data == null) {
            refreshLoginAndMove(token)
            return
        }

        initUi()
    }

    private fun initUi() {
        try {
            binding = ActivityLoginBinding.inflate(layoutInflater)
            setContentView(binding.root)
        } catch (t: Throwable) {
            Log.e("LOGIN_ACTIVITY_STARTUP_CRASH", "LoginActivity inflate/setContentView failed", t)
            Toast.makeText(this, "초기화 오류: ${t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        handleDeepLink(intent)
    }

    private fun refreshLoginAndMove(token: String) {
        lifecycleScope.launch {
            val bearer = if (token.startsWith("Bearer ")) token else "Bearer $token"
            
            // Me 정보와 쿠폰 정보를 동시에 조회하여 프리미엄 상태를 정확히 파악
            val meRes = withContext(Dispatchers.IO) { runCatching { networkService.getMe(bearer) } }
            val couponRes = withContext(Dispatchers.IO) { runCatching { networkService.getMyCoupons(bearer) } }

            meRes.onSuccess { response ->
                if (response.isSuccessful) {
                    val me = response.body()
                    if (me != null) {
                        SubscriptionHelper.saveAuthUserFromMe(this@LoginActivity, me)
                        
                        // 쿠폰 정보에서도 프리미엄 여부 확인
                        couponRes.onSuccess { cRes ->
                            if (cRes.isSuccessful) {
                                val coupons = cRes.body()
                                val isPremiumByCoupon = coupons?.any { 
                                    it.user != null && SubscriptionHelper.isPremiumPlan(it.user.subscriptionPlan) 
                                } ?: false
                                
                                if (isPremiumByCoupon) {
                                    SubscriptionHelper.markPremiumActive(this@LoginActivity)
                                }
                            }
                        }

                        startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                        finish()
                        return@launch
                    }
                }
                tokenManager.clear()
                initUi()
            }.onFailure {
                startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                finish()
            }
        }
    }

    private fun handleDeepLink(intent: Intent?) {
        handlePasswordResetDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun handlePasswordResetDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        val token = data.getQueryParameter("token") ?: return

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_container_view) as? NavHostFragment
                ?: return
        val navController = navHostFragment.navController

        if (navController.currentDestination?.id == R.id.resetPasswordFragment) return

        val args = Bundle().apply { putString("token", token) }
        navController.navigate(R.id.resetPasswordFragment, args)
    }
}