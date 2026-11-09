
package com.example.lifemaster.presentation.home.pomodoro.repository

import com.example.lifemaster.network.NetworkService
import com.example.lifemaster.network.TokenManager
import com.example.lifemaster.presentation.home.pomodoro.model.PomodoroFocusLevelRequest
import com.example.lifemaster.presentation.home.pomodoro.model.PomodoroRecentFocusResponse
import com.example.lifemaster.presentation.home.pomodoro.model.PomodoroRequest
import com.example.lifemaster.presentation.home.pomodoro.model.PomodoroResponse
import com.example.lifemaster.presentation.home.pomodoro.model.PomodoroStatsResponse
import javax.inject.Inject

class PomodoroRepository @Inject constructor(
    private val networkService: NetworkService,
    private val tokenManager: TokenManager
) {
    suspend fun registerPomodoroItem(pomodoroRequest: PomodoroRequest): Result<PomodoroResponse> = try {
        val response = networkService.registerPomodoroItem(pomodoroRequest = pomodoroRequest)
        if(response.isSuccessful && response.body() != null) {
            Result.success(response.body()!!)
        } else {
            Result.failure(Exception("Error Code: ${response.code()}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun getPomodoroAllItems(): Result<List<PomodoroResponse>> = try {
        val memberId = tokenManager.getMemberId()
            ?: return Result.failure(Exception("Member ID is null"))

        val response = networkService.getAllPomodoroItems(memberId = memberId)
        if(response.isSuccessful && response.body() != null) {
            Result.success(response.body()!!)
        } else {
            Result.failure(Exception("Error Code: ${response.code()}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun getPomodoroItemsByTodo(todoId: Int): Result<List<PomodoroResponse>> = try {
        val memberId = tokenManager.getMemberId()
            ?: return Result.failure(Exception("Member ID is null"))

        val response = networkService.getPomodoroItemsByTodo(
            memberId = memberId,
            todoId = todoId
        )
        if(response.isSuccessful && response.body() != null) {
            Result.success(response.body()!!)
        } else {
            Result.failure(Exception("Error Code: ${response.code()}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun deletePomodoroItemsByTodo(todoId: Int): Result<Int> = try {
        val response = networkService.deletePomodoroItemsByTodo(todoId = todoId)
        if(response.isSuccessful) {
            Result.success(todoId)
        } else {
            Result.failure(Exception("Error Code: ${response.code()}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun getPomodoroEscapeSentence(): Result<String> = try {
        val response = networkService.getPomodoroEscapeSentence()
        if(response.isSuccessful && response.body() != null) {
            Result.success(response.body()!!)
        } else {
            Result.failure(Exception("Error Code: ${response.code()}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun getPomodoroRecentFocus(endDate: String): Result<List<PomodoroRecentFocusResponse>> = try {
        val token = tokenManager.getBearerToken() ?: return Result.failure(Exception("Token is null"))
        val response = networkService.getPomodoroRecentFocus(token = token, endDate = endDate)
        if(response.isSuccessful && response.body() != null) {
            Result.success(response.body()!!.items)
        } else {
            Result.failure(Exception("Error Code: ${response.code()}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun getPomodoroStats(date: String): Result<PomodoroStatsResponse> = try {
        val token = tokenManager.getBearerToken() ?: return Result.failure(Exception("Token is null"))
        val response = networkService.getPomodoroStats(token = token, date = date)
        if(response.isSuccessful && response.body() != null) {
            Result.success(response.body()!!)
        } else {
            Result.failure(Exception("Error Code: ${response.code()}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun savePomodoroFocusLevel(request: PomodoroFocusLevelRequest): Result<Unit> = try {
        val token = tokenManager.getBearerToken() ?: return Result.failure(Exception("Token is null"))
        val response = networkService.savePomodoroFocusLevel(token = token, request = request)
        if(response.isSuccessful) {
            Result.success(Unit)
        } else {
            Result.failure(Exception("Error Code: ${response.code()}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}