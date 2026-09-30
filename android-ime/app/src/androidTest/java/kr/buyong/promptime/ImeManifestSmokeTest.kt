package kr.buyong.promptime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test

class ImeManifestSmokeTest {
    @Test fun appContextLoads() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertNotNull(context.packageManager)
    }
}
