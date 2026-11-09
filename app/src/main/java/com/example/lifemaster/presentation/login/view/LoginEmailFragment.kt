package com.example.lifemaster.presentation.login.view

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.lifemaster.R
import com.example.lifemaster.SubscriptionHelper
import com.example.lifemaster.databinding.FragmentLoginEmailBinding
import com.example.lifemaster.network.NetworkService
import com.example.lifemaster.network.TokenManager
import com.example.lifemaster.presentation.MainActivity
import com.example.lifemaster.presentation.total.mypage.model.MeResponse
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import javax.inject.Inject

@AndroidEntryPoint
class LoginEmailFragment : Fragment(R.layout.fragment_login_email) {

    private var _binding: FragmentLoginEmailBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var networkService: NetworkService
    @Inject lateinit var tokenManager: TokenManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLoginEmailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.includeBackButton.btnBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        binding.btnFindPassword.setOnClickListener {
            findNavController().navigate(R.id.findPasswordFragment)
        }

        binding.btnSignup.setOnClickListener {
            findNavController().navigate(R.id.signupEmailFragment)
        }

        binding.btnLogin.setOnClickListener {
            val email = binding.editEmail.text.toString().trim()
            val password = binding.editPassword.text.toString()

            when {
                email.isEmpty() || password.isEmpty() ->
                    toast("이메일과 비밀번호를 입력해주세요.")

                !Patterns.EMAIL_ADDRESS.matcher(email).matches() ->
                    toast("올바른 이메일 형식을 입력해주세요.")

                else -> login(email, password)
            }
        }
    }

    private fun login(email: String, password: String) {
        networkService.enterUserLogin(com.example.lifemaster.presentation.login.model.LoginInfo(email, password))
            .enqueue(object : Callback<String> {
                override fun onResponse(call: Call<String>, res: Response<String>) {
                    // 수정: 화면에서 이미 벗어난 경우 콜백에서 requireContext()를 호출하지 않도록 함
                    if (!isAdded) return
                    if (!res.isSuccessful) {
                        toast(parseErrorMessage(res))
                        return
                    }

                    val token = res.body()?.trim().orEmpty()
                    if (token.isBlank()) {
                        toast("로그인 토큰이 비어 있어요.")
                        return
                    }

                    // 예전 토큰 흔적 제거 후 새 토큰 저장
                    tokenManager.clear()
                    tokenManager.accessToken = token
                    tokenManager.refreshFromStorage()

                    // 로그인 직후 /users/me 로 실제 사용자 정보 조회
                    lifecycleScope.launch {
                        fetchAndSaveMeThenMove(email, token)
                    }
                }

                override fun onFailure(call: Call<String>, t: Throwable) {
                    if (!isAdded) return
                    toast("네트워크 오류가 발생했어요. 잠시 후 다시 시도해 주세요.")
                }
            })
    }

    private suspend fun fetchAndSaveMeThenMove(loginEmail: String, token: String) {
        try {
            // 수정: 이미 Bearer가 포함된 토큰에 Bearer를 중복해서 붙이지 않음
            val bearer = tokenManager.getBearerToken()
            if (bearer.isNullOrBlank()) {
                toast("로그인 토큰을 확인할 수 없어요.")
                return
            }

            val response = networkService.getMe(bearer)
            val me = response.body()
            val memberId = me?.user?.id ?: me?.id

            // 수정: 사용자 정보 조회 실패 또는 ID 누락 시 메인 화면으로 이동하지 않음
            if (!response.isSuccessful || me == null || memberId == null || memberId <= 0L) {
                Log.e("LOGIN_EMAIL", "사용자 조회 실패: HTTP ${response.code()}, memberId=$memberId")
                tokenManager.clear()
                toast("사용자 정보를 확인하지 못했어요. 다시 로그인해 주세요.")
                return
            }

            saveAuthUser(
                token = token,
                loginEmail = loginEmail,
                me = me
            )
            moveToMain(token)
        } catch (e: CancellationException) {
            // 수정: 화면 종료로 인한 코루틴 취소를 로그인 실패로 처리하지 않음
            throw e
        } catch (e: Exception) {
            Log.e("LOGIN_EMAIL", "로그인 후 사용자 정보 처리 실패", e)
            tokenManager.clear()
            if (isAdded) {
                toast("사용자 정보를 확인하는 중 오류가 발생했어요. 다시 시도해 주세요.")
            }
        }
    }

    private fun saveAuthUser(
        token: String,
        loginEmail: String,
        me: MeResponse?
    ) {
        // 토큰은 TokenManager/TokenProvider 쪽에서 이미 저장됨
        // 여기서는 부가 정보만 저장
        requireContext()
            .getSharedPreferences("auth", Context.MODE_PRIVATE)
            .edit {
                putString("loginEmail", loginEmail)
                putString("email", me?.email ?: loginEmail)

                val nick = (me?.user?.nickName ?: me?.nickName)?.trim().orEmpty()
                if (nick.isNotBlank() && nick != "null") {
                    putString("nickname", nick)
                    putString("nickName", nick)
                } else {
                    remove("nickname")
                    remove("nickName")
                }

                val memberId = me?.user?.id ?: me?.id
                if (memberId != null && memberId > 0L) {
                    putLong("memberId", memberId)
                } else {
                    remove("memberId")
                }

                val profileUrl = (me?.user?.profileImageUrl ?: me?.profileImageUrl)?.trim().orEmpty()
                if (profileUrl.isNotBlank()) {
                    putString("profileImageUrl", profileUrl)
                }
            }
        me?.let { SubscriptionHelper.persistFromMe(requireContext(), it) }
    }

    private fun moveToMain(token: String) {
        startActivity(Intent(requireActivity(), MainActivity::class.java).apply {
            putExtra("user_token", token)
        })
        requireActivity().finish()
    }

    private fun parseErrorMessage(res: Response<*>): String {
        val defaultMsg = "아이디 또는 비밀번호가 잘못되었습니다."

        if (res.code() in listOf(400, 401, 403, 404)) return defaultMsg

        val raw = try {
            res.errorBody()?.string().orEmpty()
        } catch (_: Exception) {
            ""
        }

        if (
            raw.contains("credential", true) ||
            raw.contains("password", true) ||
            raw.contains("email", true) ||
            raw.contains("user not", true) ||
            raw.contains("not found", true)
        ) {
            return defaultMsg
        }

        return Regex("\"message\"\\s*:\\s*\"([^\"]+)\"")
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?: "로그인에 실패했어요 (${res.code()})"
    }

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}