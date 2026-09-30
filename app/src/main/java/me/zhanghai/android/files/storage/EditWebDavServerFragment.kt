/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import android.app.Activity
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
import java.net.URI
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.EditWebdavServerFragmentBinding
import me.zhanghai.android.files.provider.webdav.client.AccessTokenAuthentication
import me.zhanghai.android.files.provider.webdav.client.Authority
import me.zhanghai.android.files.provider.webdav.client.NoneAuthentication
import me.zhanghai.android.files.provider.webdav.client.PasswordAuthentication
import me.zhanghai.android.files.provider.webdav.client.Protocol
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
import me.zhanghai.android.files.util.logWarning
import me.zhanghai.android.files.util.setResult
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.takeIfNotEmpty
import me.zhanghai.android.files.util.viewModels

class EditWebDavServerFragment : Fragment() {
    private val args by args<Args>()

    private val viewModel by viewModels { { EditWebDavServerViewModel() } }

    private var binding by autoCleared<EditWebdavServerFragmentBinding>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.connectState.collect { onConnectStateChanged(it) }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = EditWebdavServerFragmentBinding.inflate(inflater, container, false)
        .also { binding = it }
        .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setUpServerFormToolbar(
            binding.toolbar,
            if (args.server != null) {
                R.string.storage_edit_webdav_server_title_edit
            } else {
                R.string.storage_edit_webdav_server_title_add
            }
        )
        setUpFields()
        setUpButtons()
        if (savedInstanceState == null) {
            val server = args.server
            if (server != null) {
                fillIn(server)
            } else {
                args.host?.let { binding.hostEdit.setText(it) }
            }
        }
    }

    private fun setUpFields() {
        binding.hostEdit.hideTextInputLayoutErrorOnTextChange(binding.hostLayout)
        binding.hostEdit.doAfterTextChanged { updateNamePlaceholder() }
        binding.portEdit.hideTextInputLayoutErrorOnTextChange(binding.portLayout)
        binding.portEdit.doAfterTextChanged { updateNamePlaceholder() }
        binding.pathEdit.doAfterTextChanged { updateNamePlaceholder() }
        binding.protocolEdit.setAdapter(
            UnfilteredArrayAdapter(
                binding.protocolEdit.context,
                R.layout.dropdown_item,
                objects = getTextArray(R.array.storage_edit_webdav_server_protocol_entries)
            )
        )
        protocol = Protocol.DAVS
        binding.protocolEdit.doAfterTextChanged {
            updateNamePlaceholder()
            updatePortPlaceholder()
        }
        binding.authenticationTypeEdit.setAdapter(
            UnfilteredArrayAdapter(
                binding.authenticationTypeEdit.context,
                R.layout.dropdown_item,
                objects =
                    getTextArray(R.array.storage_edit_webdav_server_authentication_type_entries)
            )
        )
        authenticationType = AuthenticationType.PASSWORD
        binding.authenticationTypeEdit.doAfterTextChanged {
            onAuthenticationTypeChanged(authenticationType)
            updateNamePlaceholder()
        }
        binding.usernameEdit.hideTextInputLayoutErrorOnTextChange(binding.usernameLayout)
        binding.usernameEdit.doAfterTextChanged { updateNamePlaceholder() }
    }

    private fun setUpButtons() {
        binding.saveOrConnectAndAddButton.setText(
            if (args.server != null) {
                R.string.save
            } else {
                R.string.storage_edit_webdav_server_connect_and_add
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
            if (args.server != null) R.string.remove else R.string.storage_edit_webdav_server_add
        )
        binding.removeOrAddButton.setOnClickListener {
            if (args.server != null) {
                remove()
            } else {
                saveOrAdd()
            }
        }
    }

    private fun fillIn(server: WebDavServer) {
        val authority = server.authority
        binding.hostEdit.setText(authority.host)
        protocol = authority.protocol
        if (authority.port != protocol.defaultPort) {
            binding.portEdit.setText(authority.port.toString())
        }
        when (val authentication = server.authentication) {
            is PasswordAuthentication -> {
                authenticationType = AuthenticationType.PASSWORD
                binding.usernameEdit.setText(authority.username)
                binding.passwordEdit.setText(authentication.password)
            }

            is AccessTokenAuthentication -> {
                authenticationType = AuthenticationType.ACCESS_TOKEN
                binding.accessTokenEdit.setText(authentication.accessToken)
            }

            is NoneAuthentication -> authenticationType = AuthenticationType.NONE
        }
        binding.pathEdit.setText(server.relativePath)
        binding.nameEdit.setText(server.customName)
    }

    private fun updateNamePlaceholder() {
        val host = binding.hostEdit.text.toString().takeIfNotEmpty()
        val port = binding.portEdit.text.toString().takeIfNotEmpty()?.toIntOrNull()
            ?: protocol.defaultPort
        val path = binding.pathEdit.text.toString().trim()
        val username = if (authenticationType == AuthenticationType.PASSWORD) {
            binding.usernameEdit.text.toString()
        } else {
            ""
        }
        binding.nameLayout.placeholderText = if (host != null) {
            val authority = Authority(protocol, host, port, username)
            if (path.isNotEmpty()) "$authority/$path" else authority.toString()
        } else {
            getString(R.string.storage_edit_webdav_server_name_placeholder)
        }
    }

    private fun updatePortPlaceholder() {
        binding.portLayout.placeholderText = protocol.defaultPort.toString()
    }

    private var protocol: Protocol
        get() {
            val adapter = binding.protocolEdit.adapter
            val items = List(adapter.count) { adapter.getItem(it) as CharSequence }
            val selectedItem = binding.protocolEdit.text
            val selectedIndex = items.indexOfFirst { TextUtils.equals(it, selectedItem) }
            return Protocol.entries[selectedIndex]
        }
        set(value) {
            val adapter = binding.protocolEdit.adapter
            val item = adapter.getItem(value.ordinal) as CharSequence
            binding.protocolEdit.setText(item, false)
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
        binding.passwordAuthenticationLayout.isVisible =
            authenticationType == AuthenticationType.PASSWORD
        binding.accessTokenLayout.isVisible = authenticationType == AuthenticationType.ACCESS_TOKEN
    }

    private fun saveOrAdd() {
        val server = getServerOrSetError() ?: return
        Storages.addOrReplace(server)
        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun connectAndAdd() {
        if (!viewModel.connectState.value.isReady) {
            return
        }
        val server = getServerOrSetError() ?: return
        viewModel.connect(server)
    }

    private fun onConnectStateChanged(state: ActionState<WebDavServer, Unit>) {
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
                setResult(Activity.RESULT_OK)
                finish()
            }

            is ActionState.Error -> {
                val throwable = state.throwable
                throwable.logWarning("EditWebDavServerFragment", "Connect to the WebDAV server")
                showToast(throwable.toString())
                viewModel.finishConnecting()
            }
        }
    }

    private fun remove() {
        Storages.remove(args.server ?: return)
        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun getServerOrSetError(): WebDavServer? {
        val errors = ServerFormErrors<ServerFormField>()
        val host = errors.checkHost(
            binding.hostEdit.text.toString(),
            ServerFormField(binding.hostLayout, binding.hostEdit),
            R.string.storage_edit_webdav_server_host_error_empty,
            R.string.storage_edit_webdav_server_host_error_invalid
        )
        val port = errors.checkPort(
            binding.portEdit.text.toString(),
            protocol.defaultPort,
            ServerFormField(binding.portLayout, binding.portEdit),
            R.string.storage_edit_webdav_server_port_error_invalid
        )
        val path = binding.pathEdit.text.toString().trim()
        val name = binding.nameEdit.text.toString().takeIfNotEmpty()
        val (username, authentication) = when (authenticationType) {
            AuthenticationType.PASSWORD -> {
                val username = errors.checkNotEmpty(
                    binding.usernameEdit.text.toString(),
                    ServerFormField(binding.usernameLayout, binding.usernameEdit),
                    R.string.storage_edit_webdav_server_username_error_empty
                )
                username to PasswordAuthentication(binding.passwordEdit.text.toString())
            }

            AuthenticationType.ACCESS_TOKEN -> {
                val accessToken = errors.checkNotEmpty(
                    binding.accessTokenEdit.text.toString(),
                    ServerFormField(binding.accessTokenLayout, binding.accessTokenEdit),
                    R.string.storage_edit_webdav_server_access_token_error_empty
                )
                "" to accessToken?.let { AccessTokenAuthentication(it) }
            }

            AuthenticationType.NONE -> "" to NoneAuthentication
        }
        if (!errors.showOnForm() || host == null || port == null || username == null ||
            authentication == null
        ) {
            return null
        }
        val authority = Authority(protocol, host, port, username)
        return WebDavServer(args.server?.id, name, authority, authentication, path)
    }

    @Parcelize
    class Args(val server: WebDavServer? = null, val host: String? = null) : ParcelableArgs

    private enum class AuthenticationType {
        PASSWORD,
        ACCESS_TOKEN,
        NONE
    }
}
