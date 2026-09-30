package com.reamicro.fix.association.network;

import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;

public class AssociationNetworkScopeTest {
    private static final String HOST = "219.154.201.122";
    private static final String SEARCH = "http://" + HOST + ":5006/search?title=test";

    @Test public void permitsOnlyActiveRequestDuringRequest() throws Exception {
        assertFalse(AssociationNetworkScope.allowsHost(HOST));
        try (AssociationNetworkScope.Scope ignored = AssociationNetworkScope.begin(SEARCH)) {
            assertTrue(AssociationNetworkScope.allowsHost(HOST));
            assertTrue(AssociationNetworkScope.allowsUrl(new URL(SEARCH)));
            assertFalse(AssociationNetworkScope.allowsHost("example.com"));
            assertFalse(AssociationNetworkScope.allowsUrl(new URL(SEARCH + "2")));
            assertFalse(AssociationNetworkScope.allowsUrl(new URL("http://" + HOST + ":5006/login")));
        }
        assertFalse(AssociationNetworkScope.allowsHost(HOST));
    }

    @Test public void followsFanqieRequestHostPortAndPathWithoutModuleEdits() throws Exception {
        for (String url : new String[] {
            "http://219.154.201.122:5007/search", "http://api.example.org/new/search?q=test",
            "http://new.example.org:8088/book/info", "http://[::1]:9999/search",
        }) {
            URL request = new URL(url);
            try (AssociationNetworkScope.Scope ignored = AssociationNetworkScope.begin(url)) {
                assertTrue(url, AssociationNetworkScope.allowsUrl(request));
                assertTrue(url, AssociationNetworkScope.allowsHost(request.getHost()));
                assertFalse(AssociationNetworkScope.allowsHost("unrelated.example"));
            }
            assertFalse(AssociationNetworkScope.allowsHost(request.getHost()));
        }
    }

    @Test public void rejectsCredentialsFragmentsAndNonHttpProtocols() {
        for (String url : new String[] {
            "https://" + HOST + ":5006/search", "ftp://" + HOST + "/search",
            "http://user:pass@" + HOST + ":5006/search", "http://" + HOST + ":5006/search#fragment",
            "http://" + HOST + ":0/search", "http://" + HOST + ":65536/search",
            "file:///search", "not a url",
        }) try (AssociationNetworkScope.Scope ignored = AssociationNetworkScope.begin(url)) {
            assertFalse(url, AssociationNetworkScope.allowsHost(HOST));
        }
    }

    @Test public void restoresAfterExceptionAndNestedRequests() {
        try (AssociationNetworkScope.Scope outer = AssociationNetworkScope.begin(SEARCH)) {
            try (AssociationNetworkScope.Scope inner = AssociationNetworkScope.begin("https://example.com/")) {
                assertFalse(AssociationNetworkScope.allowsHost(HOST));
                throw new IllegalArgumentException("simulate request failure");
            } catch (IllegalArgumentException expected) { }
            assertTrue(AssociationNetworkScope.allowsHost(HOST));
        }
        assertFalse(AssociationNetworkScope.allowsHost(HOST));
    }

    @Test public void doesNotLeakToOtherOrChildThreads() throws Exception {
        AtomicBoolean leaked = new AtomicBoolean(true);
        try (AssociationNetworkScope.Scope ignored = AssociationNetworkScope.begin(SEARCH)) {
            Thread thread = new Thread(() -> leaked.set(AssociationNetworkScope.allowsHost(HOST)));
            thread.start(); thread.join();
            assertFalse(leaked.get());
            assertTrue(AssociationNetworkScope.allowsHost(HOST));
        }
    }

    @Test public void repeatedCloseIsHarmless() {
        AssociationNetworkScope.Scope scope = AssociationNetworkScope.begin(SEARCH);
        scope.close(); scope.close();
        assertFalse(AssociationNetworkScope.allowsHost(HOST));
    }
}
