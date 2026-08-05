package com.espaillat.healthsync

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.espaillat.healthsync.databinding.ActivityOwnerPickerBinding

/** First-run launcher activity: two-button Ozzy/Max picker, persisted then skipped forever after. */
class OwnerPickerActivity : AppCompatActivity() {

    private lateinit var syncState: SyncState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        syncState = SyncState(this)

        if (syncState.owner != null) {
            goToMain()
            return
        }

        val binding = ActivityOwnerPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonOzzy.setOnClickListener { pickOwner(Owner.OZZY) }
        binding.buttonMax.setOnClickListener { pickOwner(Owner.MAX) }
    }

    private fun pickOwner(owner: Owner) {
        syncState.owner = owner
        goToMain()
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
