package com.example.lifemaster.presentation.login.view

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import com.example.lifemaster.R
import com.example.lifemaster.SubscriptionHelper
import com.example.lifemaster.databinding.ActivityLoginBinding
import com.example.lifemaster.network.NetworkService
import com.example.lifemaster.network.TokenManager
import com.example.lifemaster.presentation.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class LoginActivity : AppCompatActivity() {

    @Inject
    lateinit var tokenManager: TokenManager

    @Inject
    lateinit var networkService: NetworkService

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.clearFlags(
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        // 먼저 로그인 화면을 정상적으로 표시
        initUi()

        // 비밀번호 재설정 등의 딥링크가 아닌 경우에만 자동 로그인 확인
        if (intent?.data == null) {
            val token = tokenManager.getBearerToken()

            if (!token.isNullOrBlank()) {
                refreshLoginAndMove(token)
            }
        }
    }

    private fun initUi() {
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        handleDeepLink(intent)
    }

    private fun refreshLoginAndMove(token: String) {
        lifecycleScope.launch {

            val bearer =
                if (token.startsWith("Bearer ")) {
                    token
                } else {
                    "Bearer $token"
                }

            val meResult = withContext(Dispatchers.IO) {
                runCatching {
                    networkService.getMe(bearer)
                }
            }

            val meResponse = meResult.getOrElse { error ->
                Log.e(
                    "LoginActivity",
                    "자동 로그인 확인 중 네트워크 오류",
                    error
                )

                Toast.makeText(
                    this@LoginActivity,
                    "네트워크 연결을 확인해주세요.",
                    Toast.LENGTH_SHORT
                ).show()

                return@launch
            }

            if (!meResponse.isSuccessful) {
                Log.e(
                    "LoginActivity",
                    "자동 로그인 실패 code=${meResponse.code()}"
                )

                // 만료되거나 사용할 수 없는 토큰 제거
                tokenManager.clear()
                return@launch
            }

            val me = meResponse.body()

            if (me == null) {
                Log.e(
                    "LoginActivity",
                    "자동 로그인 실패: 사용자 정보 없음"
                )

                tokenManager.clear()
                return@launch
            }

            SubscriptionHelper.saveAuthUserFromMe(
                this@LoginActivity,
                me
            )

            // 쿠폰 기반 프리미엄 여부 확인
            val couponResult = withContext(Dispatchers.IO) {
                runCatching {
                    networkService.getMyCoupons(bearer)
                }
            }

            couponResult.onSuccess { response ->

                if (response.isSuccessful) {

                    val coupons = response.body()

                    val isPremiumByCoupon =
                        coupons?.any { coupon ->
                            coupon.user != null &&
                                    SubscriptionHelper.isPremiumPlan(
                                        coupon.user.subscriptionPlan
                                    )
                        } ?: false

                    if (isPremiumByCoupon) {
                        SubscriptionHelper.markPremiumActive(
                            this@LoginActivity
                        )
                    }
                }
            }.onFailure { error ->
                // 쿠폰 조회 실패는 로그인 자체를 막지 않음
                Log.e(
                    "LoginActivity",
                    "쿠폰 정보 조회 실패",
                    error
                )
            }

            startActivity(
                Intent(
                    this@LoginActivity,
                    MainActivity::class.java
                )
            )

            finish()
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
            supportFragmentManager.findFragmentById(
                R.id.fragment_container_view
            ) as? NavHostFragment ?: return

        val navController =
            navHostFragment.navController

        if (
            navController.currentDestination?.id ==
            R.id.resetPasswordFragment
        ) {
            return
        }

        val args = Bundle().apply {
            putString("token", token)
        }

        navController.navigate(
            R.id.resetPasswordFragment,
            args
        )
    }
}