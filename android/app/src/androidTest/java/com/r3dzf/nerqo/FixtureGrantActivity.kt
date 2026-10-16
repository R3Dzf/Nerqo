package com.r3dzf.nerqo

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract

/** Test APK only: the fixture owner grants access like the system picker. */
class FixtureGrantActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val authority = "com.r3dzf.nerqo.test.documents"
        contentResolver.acquireContentProviderClient(authority)?.close()
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        for (id in listOf("root", "root/download.bin", "root/upload.bin", "root/cancel.bin", "root/ui-resume.bin"))
            grantUriPermission("com.r3dzf.nerqo", DocumentsContract.buildDocumentUri(authority, id), flags)
        grantUriPermission("com.r3dzf.nerqo", DocumentsContract.buildTreeDocumentUri(authority, "root"), flags)
        finish()
    }
}
