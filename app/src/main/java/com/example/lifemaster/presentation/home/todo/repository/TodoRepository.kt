
package com.example.lifemaster.presentation.home.todo.repository

import com.example.lifemaster.network.NetworkService
import com.example.lifemaster.network.TokenManager
import com.example.lifemaster.presentation.home.todo.model.TodoRequest
import com.example.lifemaster.presentation.home.todo.model.TodoResponse
import javax.inject.Inject

class TodoRepository @Inject constructor(
    private val networkService: NetworkService,
    private val tokenManager: TokenManager
) {

    // 새 할일 생성
    suspend fun addTodoItem(request: TodoRequest): Result<TodoResponse> = try {
        val response = networkService.addTodoItem(request = request)
        Result.success(response)
    } catch (e: Exception) {
        Result.failure(e)
    }

    // 모든 할일 조회
    suspend fun getTodoItems(): Result<List<TodoResponse>> = try {
        val memberId = tokenManager.getMemberId()
            ?: return Result.failure(Exception("Member ID is null"))

        val response = networkService.getTodoItems(memberId = memberId)
        Result.success(response)
    } catch (e: Exception) {
        Result.failure(e)
    }

    // 날짜별 할일 조회
    suspend fun getTodoItemsByDate(date: String): Result<List<TodoResponse>> = try {
        val response = networkService.getTodoItemsByDate(date = date)
        Result.success(response)
    } catch (e: Exception) {
        Result.failure(e)
    }

    // 할일 삭제
    suspend fun deleteTodoItem(deleteId: Int): Result<Int> = try {
        networkService.deleteTodoItem(id = deleteId)
        Result.success(deleteId)
    } catch (e: Exception) {
        Result.failure(e)
    }

    // 할일 수정
    suspend fun updateItem(id: Int, date: String, title: String): Result<TodoResponse> = try {
        val response = networkService.updateTodoItem(id = id, date = date, title = title)
        Result.success(response)
    } catch (e: Exception) {
        Result.failure(e)
    }

    // 할일 토글
    suspend fun toggleItem(id: Int): Result<TodoResponse> = try {
        val response = networkService.toggleTodoItem(id = id)
        Result.success(response)
    } catch (e: Exception) {
        Result.failure(e)
    }
}