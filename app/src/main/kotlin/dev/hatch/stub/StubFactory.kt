package dev.hatch.stub

import android.content.Context
import com.android.apksig.ApkSigner
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import java.io.File

/**
 * Template stub APK → per-app APK (package, label, icon, appId), then v2/v3 sign.
 * Template ships in assets/stub-template.apk once the stub project is built into the host.
 */
object StubFactory {
    fun build(
        context: Context?,
        template: File,
        pkg: String,
        label: String,
        appId: String,
        iconPng: ByteArray,
        signer: StubKeys.Signer,
        out: File,
    ) {
        val apk = ApkModule.loadApkFile(template)
        val m = apk.androidManifest
        m.packageName = pkg
        m.setApplicationLabel(label)
        val app = m.applicationElement
        val it = app.recursiveElements()
        while (it.hasNext()) {
            val md = it.next()
            if (md.name == "meta-data") {
                md.searchAttributeByResourceId(0x01010024)?.setValueAsString(appId) // android:value
            }
        }
        m.refreshFull()
        apk.add(ByteInputSource(iconPng, "res/mipmap/ic_app.png"))
        val unsigned = File(out.path + ".unsigned")
        apk.writeApk(unsigned)

        val sc = ApkSigner.SignerConfig.Builder("stub", signer.key, listOf(signer.cert)).build()
        ApkSigner.Builder(listOf(sc))
            .setInputApk(unsigned)
            .setOutputApk(out)
            .setV1SigningEnabled(false)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setMinSdkVersion(29)
            .build()
            .sign()
        unsigned.delete()
    }
}
