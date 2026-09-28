package com.yunian.ai.feature.profile

import android.content.Context
import com.yunian.ai.database.repository.UserRepository
import com.yunian.ai.domain.ServiceRegistry
import com.yunian.ai.domain.UserProfileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class UserProfileProviderImpl(
    context: Context,
    repository: UserRepository? = null
) : UserProfileProvider {

    private val repository: UserRepository = repository
        ?: ServiceRegistry.get(UserRepository::class.java)
        ?: UserRepository(context.applicationContext)

    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun getUserId(): String = "default_user"

    override fun getNickname(): String = repository.userName.value

    override fun getAvatar(): String? = repository.userAvatar.value

    override fun observeAvatar(onChange: (String?) -> Unit): () -> Unit {
        val job = observerScope.launch {
            repository.userAvatar.collect { avatar ->
                onChange(avatar)
            }
        }
        return { job.cancel() }
    }

    override fun observeNickname(onChange: (String) -> Unit): () -> Unit {
        val job = observerScope.launch {
            repository.userName.collect { name ->
                onChange(name)
            }
        }
        return { job.cancel() }
    }

    override fun isLoggedIn(): Boolean = true
}
