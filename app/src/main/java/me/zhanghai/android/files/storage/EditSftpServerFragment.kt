/*
 * Copyright (c) 2021 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hierynomus.sshj.common.KeyDecryptionFailedException
import java.net.URI
import java8.nio.file.Path
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.EditSftpServerFragmentBinding
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.provider.sftp.client.Authority
import me.zhanghai.android.files.provider.sftp.client.HostKeyChange
import me.zhanghai.android.files.provider.sftp.client.PasswordAuthentication
import me.zhanghai.android.files.provider.sftp.client.PublicKeyAuthentication
import me.zhanghai.android.files.provider.sftp.client.hostKeyChange
import me.zhanghai.android.files.ui.UnfilteredArrayAdapter
import me.zhanghai.android.files.util.ActionState
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.autoCleared
import me.zhanghai.android.files.util.fadeToVisibilityUnsafe
import me.zhanghai.android.files.util.finish
import me.zhanghai.android.files.util.getTextArray
import me.zhanghai.android.files.util.hideTextInputLayoutErrorOnTextChange
import me.zhanghai.android.files.util.isReady
import me.zhanghai.android.files.util.launchSafe
import me.zhanghai.android.files.util.logWarning
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.takeIfNotEmpty
import me.zhanghai.android.files.util.viewModels

class EditSftpServerFragment :
    Fragment(),
    SftpHostKeyChangedDialogFragment.Listener {
    private val openPrivateKeyFileLauncher = registerForActivityResult(
        FileListActivity.OpenFileContract(),
        this::onOpenPrivateKeyFileResult
    )

    private val args by args<Args>()

    private val viewModel by viewModels { { EditSftpServerViewModel() } }

    private var binding by autoCleared<EditSftpServerFragmentBinding>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.readPrivateKeyFileState.collect {
                        onReadPrivateKeyFileStateChanged(it)
                    }
                }
                viewModel.connectState.collect { onConnectStateChanged(it) }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = EditSftpServerFragmentBinding.inflate(inflater, container, false)
        .also { binding = it }
        .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setUpServerFormToolbar(
            binding.toolbar,
            if (args.server != null) {
                R.string.storage_edit_sftp_server_title_edit
            } else {
                R.string.storage_edit_sftp_server_title_add
            }
        )
        setUpFields()
        setUpButtons()
        if (savedInstanceState == null) {
            args.server?.let { fillIn(it) }
        }
    }

    private fun setUpFields() {
        binding.hostEdit.hideTextInputLayoutErrorOnTextChange(binding.hostLayout)
        binding.hostEdit.doAfterTextChanged { updateNamePlaceholder() }
        binding.portEdit.hideTextInputLayoutErrorOnTextChange(binding.portLayout)
        binding.portEdit.doAfterTextChanged { updateNamePlaceholder() }
        binding.pathEdit.doAfterTextChanged { updateNamePlaceholder() }
        binding.authenticationTypeEdit.setAdapter(
            UnfilteredArrayAdapter(
                binding.authenticationTypeEdit.context,
                R.layout.dropdown_item,
                objects = getTextArray(R.array.storage_edit_sftp_server_authentication_type_entries)
            )
        )
        authenticationType = AuthenticationType.PASSWORD
        binding.authenticationTypeEdit.doAfterTextChanged {
            onAuthenticationTypeChanged(authenticationType)
        }
        binding.usernameEdit.hideTextInputLayoutErrorOnTextChange(binding.usernameLayout)
        binding.usernameEdit.doAfterTextChanged { updateNamePlaceholder() }
        binding.privateKeyLayout.setEndIconOnClickListener { onOpenPrivateKeyFile() }
        binding.privateKeyEdit.hideTextInputLayoutErrorOnTextChange(
            binding.privateKeyLayout,
            binding.privateKeyPasswordLayout
        )
        binding.privateKeyPasswordEdit.hideTextInputLayoutErrorOnTextChange(
            binding.privateKeyLayout,
            binding.privateKeyPasswordLayout
        )
    }

    private fun setUpButtons() {
        binding.saveOrConnectAndAddButton.setText(
            if (args.server != null) {
                R.string.save
            } else {
                R.string.storage_edit_sftp_server_connect_and_add
            }
        )
        binding.saveOrConnectAndAddButton.setOnClickListener {
            if (args.server != null) {
                saveOrAdd()
            } else {
                connectAndAdd()
            }
        }
        binding.cancelButton.setOnClickListener { finish() }
        binding.removeOrAddButton.setText(
            if (args.server != null) R.string.remove else R.string.storage_edit_sftp_server_add
        )
        binding.removeOrAddButton.setOnClickListener {
            if (args.server != null) {
                remove()
            } else {
                saveOrAdd()
            }
        }
    }

    private fun fillIn(server: SftpServer) {
        val authority = server.authority
        binding.hostEdit.setText(authority.host)
        if (authority.port != Authority.DEFAULT_PORT) {
            binding.portEdit.setText(authority.port.toString())
        }
        binding.usernameEdit.setText(authority.username)
        when (val authentication = server.authentication) {
            is PasswordAuthentication -> {
                authenticationType = AuthenticationType.PASSWORD
                binding.passwordEdit.setText(authentication.password)
            }

            is PublicKeyAuthentication -> {
                authenticationType = AuthenticationType.PUBLIC_KEY
                binding.privateKeyEdit.setText(authentication.privateKey)
                binding.privateKeyPasswordEdit.setText(authentication.privateKeyPassword)
            }
        }
        binding.pathEdit.setText(server.relativePath)
        binding.nameEdit.setText(server.customName)
    }

    private fun updateNamePlaceholder() {
        val host = binding.hostEdit.text.toString().takeIfNotEmpty()
        val port = binding.portEdit.text.toString().takeIfNotEmpty()?.toIntOrNull()
            ?: Authority.DEFAULT_PORT
        val path = binding.pathEdit.text.toString().trim()
        val username = binding.usernameEdit.text.toString()
        binding.nameLayout.placeholderText = if (host != null) {
            val authority = Authority(host, port, username)
            if (path.isNotEmpty()) "$authority/$path" else authority.toString()
        } else {
            getString(R.string.storage_edit_sftp_server_name_placeholder)
        }
    }

    private var authenticationType: AuthenticationType
        get() {
            val adapter = binding.authenticationTypeEdit.adapter
            val items = List(adapter.count) { adapter.getItem(it) as CharSequence }
            val selectedItem = binding.authenticationTypeEdit.text
            val selectedIndex = items.indexOfFirst { TextUtils.equals(it, selectedItem) }
            return AuthenticationType.entries[selectedIndex]
        }
        set(value) {
            val adapter = binding.authenticationTypeEdit.adapter
            val item = adapter.getItem(value.ordinal) as CharSequence
            binding.authenticationTypeEdit.setText(item, false)
            onAuthenticationTypeChanged(value)
        }

    private fun onAuthenticationTypeChanged(authenticationType: AuthenticationType) {
        binding.passwordLayout.isVisible = authenticationType == AuthenticationType.PASSWORD
        binding.publicKeyAuthenticationLayout.isVisible =
            authenticationType == AuthenticationType.PUBLIC_KEY
    }

    private fun onOpenPrivateKeyFile() {
        if (!viewModel.readPrivateKeyFileState.value.isReady) {
            return
        }
        openPrivateKeyFileLauncher.launchSafe(listOf(MimeType.ANY), this)
    }

    private fun onOpenPrivateKeyFileResult(result: Path?) {
        result ?: return
        viewModel.readPrivateKeyFile(result)
    }

    private fun onReadPrivateKeyFileStateChanged(state: ActionState<Path, String>) {
        when (state) {
            is ActionState.Ready, is ActionState.Running -> {
                val isReading = state is ActionState.Running
                binding.privateKeyLayout.placeholderText =
                    if (isReading) getString(R.string.loading) else null
                if (isReading) {
                    binding.privateKeyEdit.text = null
                }
            }

            is ActionState.Success -> {
                binding.privateKeyEdit.setText(state.result)
                viewModel.finishReadingPrivateKeyFile()
            }

            is ActionState.Error -> {
                val throwable = state.throwable
                throwable.logWarning("EditSftpServerFragment", "Read the private key file")
                showToast(throwable.toString())
                viewModel.finishReadingPrivateKeyFile()
            }
        }
    }

    private fun saveOrAdd() {
        val server = getServerOrSetError() ?: return
        Storages.addOrReplace(server)
        finish()
    }

    private fun connectAndAdd() {
        if (!viewModel.connectState.value.isReady) {
            return
        }
        val server = getServerOrSetError() ?: return
        viewModel.connect(server)
    }

    private fun onConnectStateChanged(state: ActionState<SftpServer, Unit>) {
        when (state) {
            is ActionState.Ready, is ActionState.Running -> {
                val isConnecting = state is ActionState.Running
                binding.progress.fadeToVisibilityUnsafe(isConnecting)
                binding.scrollView.fadeToVisibilityUnsafe(!isConnecting)
                binding.saveOrConnectAndAddButton.isEnabled = !isConnecting
                binding.removeOrAddButton.isEnabled = !isConnecting
            }

            is ActionState.Success -> {
                Storages.addOrReplace(state.argument)
                finish()
            }

            is ActionState.Error -> {
                val throwable = state.throwable
                throwable.logWarning("EditSftpServerFragment", "Connect to the SFTP server")
                val hostKeyChange = throwable.hostKeyChange
                if (hostKeyChange != null) {
                    SftpHostKeyChangedDialogFragment.show(hostKeyChange, this)
                } else {
                    showToast(throwable.toString())
                }
                viewModel.finishConnecting()
            }
        }
    }

    override fun trustHostKey(change: HostKeyChange) {
        SftpServerHostKeyStore.putHostKey(change.host, change.port, change.keyType, change.newKey)
        connectAndAdd()
    }

    private fun remove() {
        Storages.remove(args.server ?: return)
        finish()
    }

    private fun getServerOrSetError(): SftpServer? {
        val errors = ServerFormErrors<ServerFormField>()
        val host = errors.checkHost(
            binding.hostEdit.text.toString(),
            ServerFormField(binding.hostLayout, binding.hostEdit),
            R.string.storage_edit_sftp_server_host_error_empty,
            R.string.storage_edit_sftp_server_host_error_invalid
        )
        val port = errors.checkPort(
            binding.portEdit.text.toString(),
            Authority.DEFAULT_PORT,
            ServerFormField(binding.portLayout, binding.portEdit),
            R.string.storage_edit_sftp_server_port_error_invalid
        )
        val path = binding.pathEdit.text.toString().trim()
        val name = binding.nameEdit.text.toString().takeIfNotEmpty()
        val username = errors.checkNotEmpty(
            binding.usernameEdit.text.toString(),
            ServerFormField(binding.usernameLayout, binding.usernameEdit),
            R.string.storage_edit_sftp_server_username_error_empty
        )
        val authentication = when (authenticationType) {
            AuthenticationType.PASSWORD ->
                PasswordAuthentication(binding.passwordEdit.text.toString())

            AuthenticationType.PUBLIC_KEY -> getPublicKeyAuthenticationOrSetError(errors)
        }
        if (!errors.showOnForm() || host == null || port == null || username == null ||
            authentication == null
        ) {
            return null
        }
        val authority = Authority(host, port, username)
        return SftpServer(args.server?.id, name, authority, authentication, path)
    }

    private fun getPublicKeyAuthenticationOrSetError(
        errors: ServerFormErrors<ServerFormField>
    ): PublicKeyAuthentication? {
        val privateKeyField = ServerFormField(binding.privateKeyLayout, binding.privateKeyEdit)
        val privateKey = errors.checkNotEmpty(
            binding.privateKeyEdit.text.toString(),
            privateKeyField,
            R.string.storage_edit_sftp_server_private_key_error_empty
        ) ?: return null
        val privateKeyPassword = binding.privateKeyPasswordEdit.text.toString().takeIfNotEmpty()
        val exception = PublicKeyAuthentication.validate(privateKey, privateKeyPassword)
        if (exception != null) {
            exception.logWarning("EditSftpServerFragment", "Validate the private key")
            if (exception is KeyDecryptionFailedException) {
                errors.add(
                    ServerFormField(
                        binding.privateKeyPasswordLayout,
                        binding.privateKeyPasswordEdit
                    ),
                    R.string.storage_edit_sftp_server_private_key_password_error_invalid
                )
            } else {
                errors.add(
                    privateKeyField,
                    R.string.storage_edit_sftp_server_private_key_error_invalid
                )
            }
            return null
        }
        return PublicKeyAuthentication(privateKey, privateKeyPassword)
    }

    @Parcelize
    class Args(val server: SftpServer? = null) : ParcelableArgs

    private enum class AuthenticationType {
        PASSWORD,
        PUBLIC_KEY
    }
}
