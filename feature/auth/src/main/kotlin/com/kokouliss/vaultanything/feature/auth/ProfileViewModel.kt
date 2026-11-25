package com.kokouliss.vaultanything.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kokouliss.vaultanything.core.domain.model.User
import com.kokouliss.vaultanything.core.domain.repository.AuthRepository
import com.kokouliss.vaultanything.core.domain.repository.LockRepository
import com.kokouliss.vaultanything.core.domain.repository.PinResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where deleting the account stands. Success needs no state: the app leaves this screen. */
internal enum class DeleteState { Idle, Deleting, Failed }

@HiltViewModel
internal class ProfileViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val lockRepository: LockRepository,
) : ViewModel() {

    val hasPin: StateFlow<Boolean> = lockRepository.hasPin.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    suspend fun setPin(pin: String, current: String?): PinResult = lockRepository.setPin(pin, current)

    suspend fun removePin(current: String): PinResult = lockRepository.removePin(current)

    /** [idToken] is null when Google did not hand over a fresh sign-in token. */
    suspend fun confirmIdentity(idToken: String?): Boolean = idToken != null && authRepository.confirmIdentity(idToken)

    suspend fun confirmIdentityWithPassword(password: String): Boolean = authRepository.confirmIdentityWithPassword(password)

    /** Null for a moment while loading, and again right after signing out, when the app leaves this screen. */
    val user: StateFlow<User?> = authRepository.currentUser
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun signOut() {
        viewModelScope.launch { authRepository.signOut() }
    }

    private val _deleteState = MutableStateFlow(DeleteState.Idle)
    val deleteState: StateFlow<DeleteState> = _deleteState.asStateFlow()

    /** [idToken] is null when Google did not hand over a fresh sign-in token. */
    fun deleteAccount(idToken: String?) = delete { idToken != null && authRepository.deleteAccount(idToken) }

    fun deleteAccountWithPassword(password: String) = delete { authRepository.deleteAccountWithPassword(password) }

    private fun delete(attempt: suspend () -> Boolean) {
        viewModelScope.launch {
            _deleteState.value = DeleteState.Deleting
            _deleteState.value = if (attempt()) DeleteState.Idle else DeleteState.Failed
        }
    }

    private val _verificationSent = MutableStateFlow(false)
    val verificationSent: StateFlow<Boolean> = _verificationSent.asStateFlow()

    fun sendVerificationEmail() {
        viewModelScope.launch { _verificationSent.value = authRepository.sendVerificationEmail() }
    }

    init {
        // Opening Settings after tapping the link shows the address as confirmed right away.
        viewModelScope.launch { authRepository.refreshEmailVerification() }
    }
}
