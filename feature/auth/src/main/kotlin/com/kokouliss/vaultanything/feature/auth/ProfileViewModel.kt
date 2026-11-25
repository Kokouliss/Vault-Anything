package com.kokouliss.vaultanything.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kokouliss.vaultanything.core.domain.model.Emergency
import com.kokouliss.vaultanything.core.domain.model.Member
import com.kokouliss.vaultanything.core.domain.model.Role
import com.kokouliss.vaultanything.core.domain.model.Trust
import com.kokouliss.vaultanything.core.domain.model.User
import com.kokouliss.vaultanything.core.domain.repository.AuthRepository
import com.kokouliss.vaultanything.core.domain.repository.EmergencyRepository
import com.kokouliss.vaultanything.core.domain.repository.LockRepository
import com.kokouliss.vaultanything.core.domain.repository.PinResult
import com.kokouliss.vaultanything.core.domain.repository.SpaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where deleting the account stands. Success needs no state: the app leaves this screen. */
internal enum class DeleteState { Idle, Deleting, Failed }

@HiltViewModel
internal class ProfileViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val lockRepository: LockRepository,
    private val emergencyRepository: EmergencyRepository,
    spaceRepository: SpaceRepository,
) : ViewModel() {

    val hasPin: StateFlow<Boolean> = lockRepository.hasPin.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val emergency: StateFlow<Emergency?> = emergencyRepository.own.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Everyone in the person's spaces by id, for the names in the emergency section. */
    val names: StateFlow<Map<String, String>> = spaceRepository.observeSpaces()
        .map { spaces -> spaces.flatMap { it.members }.associate { it.id to it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** The other adults in the person's spaces: who can be the trusted person. */
    val adults: StateFlow<List<Member>> = combine(spaceRepository.observeSpaces(), authRepository.currentUser) { spaces, user ->
        spaces.flatMap { it.members }.filter { it.role == Role.ADULT && it.id != user?.id }.distinctBy { it.id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The people who named the person, while they still share a space (the server opens nothing otherwise). */
    val trusting: StateFlow<List<Trust>> = combine(emergencyRepository.trusting, names) { trusting, names -> trusting.filter { it.ownerId in names } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Also takes back a request that is waiting, which is how the person says no. */
    fun setEmergency(contactId: String, waitDays: Int) {
        viewModelScope.launch { emergencyRepository.set(contactId, waitDays) }
    }

    fun removeEmergency() {
        viewModelScope.launch { emergencyRepository.remove() }
    }

    fun askEmergency(ownerId: String) {
        viewModelScope.launch { emergencyRepository.ask(ownerId) }
    }

    suspend fun openEmergency(ownerId: String): Boolean = emergencyRepository.open(ownerId)

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
