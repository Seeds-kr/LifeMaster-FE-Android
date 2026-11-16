
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

    private var loginCall: Call<String>? = null
    private var loginInProgress = false

    private fun setLoginInProgress(inProgress: Boolean) {
        loginInProgress = inProgress
        _binding?.btnLogin?.isEnabled = !inProgress
    }

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
        if (loginInProgress) return
        setLoginInProgress(true)

        try {
            loginCall = networkService.enterUserLogin(
                com.example.lifemaster.presentation.login.model.LoginInfo(email, password)
            )

            loginCall?.enqueue(object : Callback<String> {
                override fun onResponse(call: Call<String>, res: Response<String>) {
                    loginCall = null

                    if (!isAdded || _binding == null) return

                    if (!res.isSuccessful) {
                        setLoginInProgress(false)
                        toast(parseErrorMessage(res))
                        return
                    }

                    val token = res.body()?.trim().orEmpty()
                    if (token.isBlank()) {
                        setLoginInProgress(false)
                        toast("로그인 토큰이 비어 있어요.")
                        return
                    }

                    try {
                        tokenManager.clear()
                        tokenManager.accessToken = token
                        tokenManager.refreshFromStorage()
                    } catch (e: Exception) {
                        Log.e("LOGIN_EMAIL", "로그인 토큰 저장 실패", e)
                        setLoginInProgress(false)
                        toast("로그인 정보를 저장하지 못했어요. 다시 시도해 주세요.")
                        return
                    }

                    viewLifecycleOwner.lifecycleScope.launch {
                        fetchAndSaveMeThenMove(email, token)
                    }
                }

                override fun onFailure(call: Call<String>, t: Throwable) {
                    loginCall = null

                    if (!isAdded || _binding == null) return

                    setLoginInProgress(false)

                    if (call.isCanceled) return

                    Log.e("LOGIN_EMAIL", "로그인 네트워크 오류", t)
                    toast("네트워크 오류가 발생했어요. 잠시 후 다시 시도해 주세요.")
                }
            })
        } catch (e: Exception) {
            loginCall = null
            setLoginInProgress(false)

            Log.e("LOGIN_EMAIL", "로그인 요청 시작 실패", e)

            if (isAdded && _binding != null) {
                toast("로그인 요청 중 오류가 발생했어요.")
            }
        }
    }

    private suspend fun fetchAndSaveMeThenMove(loginEmail: String, token: String) {
        try {
            val bearer = tokenManager.getBearerToken()
            if (bearer.isNullOrBlank()) {
                toast("로그인 토큰을 확인할 수 없어요.")
                return
            }

            val response = networkService.getMe(bearer)
            val me = response.body()
            val memberId = me?.user?.id ?: me?.id

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
            throw e
        } catch (e: Exception) {
            Log.e("LOGIN_EMAIL", "로그인 후 사용자 정보 처리 실패", e)
            tokenManager.clear()

            if (isAdded && _binding != null) {
                toast("사용자 정보를 확인하는 중 오류가 발생했어요. 다시 시도해 주세요.")
            }
        } finally {
            if (_binding != null) {
                setLoginInProgress(false)
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
        loginCall?.cancel()
        loginCall = null
        loginInProgress = false
        super.onDestroyView()
        _binding = null
    }
}