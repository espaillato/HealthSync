package com.espaillat.healthsync

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.espaillat.healthsync.databinding.ActivityOwnerPickerBinding

/**
 * First-run launcher activity: free-text name entry, persisted then skipped forever after.
 * There's deliberately no fixed list of choices here -- this app is meant to be installed on
 * however many phones a household uses it on, each one entering its own name once. See
 * [SyncState.owner] for how that value is used downstream (Drive CSV filename, the `owner`
 * column in every synced row).
 */
class OwnerPickerActivity : AppCompatActivity() {

    private lateinit var syncState: SyncState
    private lateinit var binding: ActivityOwnerPickerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        syncState = SyncState(this)

        if (syncState.owner != null) {
            goToMain()
            return
        }

        binding = ActivityOwnerPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonConfirmOwner.setOnClickListener {
            val name = binding.editOwnerName.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                binding.inputOwnerName.error = getString(R.string.error_owner_name_blank)
                return@setOnClickListener
            }
            syncState.owner = name
            goToMain()
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
