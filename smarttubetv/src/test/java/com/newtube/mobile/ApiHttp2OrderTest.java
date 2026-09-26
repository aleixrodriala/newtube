package com.newtube.mobile;

import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.liskovsoft.sharedutils.okhttp.OkHttpManager;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.shadows.ShadowLog;

import java.util.List;

import okhttp3.Protocol;

/**
 * Round 3 regression (2026-09-26): an early VideoInfoService.instance() built the shared OkHttp
 * client before onCreate reached setPreferHttp2(true), so every InnerTube call ran on HTTP/1.1 -
 * without the H2 PING that keeps a carrier NAT mapping alive (a /player then stalled 8 s on a
 * socket idle for 76 s). The flag now rides the Application class's static initializer.
 *
 * <p>sdk 27 on purpose: no other test uses it, so this class gets its own Robolectric sandbox and
 * the Application class's static initializer is observed fresh, not inherited from another test.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 27, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ApiHttp2OrderTest {
    @After
    public void tearDown() {
        OkHttpManager.unhold();
    }

    @Test
    public void loadingTheApplicationClassUnpinsHttp2BeforeAnyClientCanBeBuilt() throws Exception {
        Class.forName(MobileMainApplication.class.getName(), true,
                MobileMainApplication.class.getClassLoader());
        OkHttpManager.unhold(); // whatever another test built: build afresh, as a new process would

        List<Protocol> protocols = OkHttpManager.instance().getClient().protocols();
        assertTrue(protocols.toString(), protocols.contains(Protocol.HTTP_2));
    }

    @Test
    public void aLateSwitchIsReportedInsteadOfSilentlyIgnored() {
        OkHttpManager.instance().getClient();
        ShadowLog.clear();
        try {
            OkHttpManager.setPreferHttp2(true);

            assertTrue(ShadowLog.getLogsForTag("NetPath").stream().anyMatch(
                    item -> item.msg.startsWith("api-client prefer-http2=true IGNORED")));
        } finally {
            // Leave the flag as a fresh process has it, so the other test proves the static
            // initializer rather than this test's call.
            OkHttpManager.unhold();
            OkHttpManager.setPreferHttp2(false);
        }
    }
}
