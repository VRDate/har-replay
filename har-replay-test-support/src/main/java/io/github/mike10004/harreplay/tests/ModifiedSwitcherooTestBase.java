package io.github.mike10004.harreplay.tests;

import com.github.mike10004.xvfbtesting.XvfbRule;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.net.HostAndPort;
import io.github.mike10004.harreplay.ReplaySessionControl;
import io.github.mike10004.harreplay.tests.Fixtures.Fixture;
import io.github.mike10004.harreplay.tests.Fixtures.FixturesRule;
import io.github.mike10004.harreplay.tests.ReplayManagerTester.ReplayClient;
import org.apache.commons.lang3.StringUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.Timeout;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public abstract class ModifiedSwitcherooTestBase {
    @ClassRule
    public static FixturesRule fixturesRule = Fixtures.asRule();

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Rule
    public final XvfbRule xvfb = XvfbRule.builder().build();

    @Rule
    public final Timeout timeout = new Timeout(120, TimeUnit.SECONDS);

    private static Set<ChromeDriver> chromeDriverInstances;

    @ClassRule
    public static Fixtures.ChromeDriverSetupRule chromeDriverSetupRule = new Fixtures.ChromeDriverSetupRule();

    @BeforeClass
    public static void initChromeDriver() {
        chromeDriverInstances = new HashSet<>();
    }

    @AfterClass
    public static void quitChromeDrivers() {
        chromeDriverInstances.forEach(driver -> {
            System.out.println("@AfterClass quitting " + driver);
            driver.quit();
            System.out.println("@AfterClass did quit " + driver);
        });
    }

    @Test
    public void testExtensionWithSelenium_https() throws Exception {
        System.out.println("testExtensionWithSelenium_https");
        testExtensionWithSelenium(fixturesRule.getFixtures().https());
    }

    @Test
    public void testExtensionWithSelenium_httpsRedirect() throws Exception {
        System.out.println("testExtensionWithSelenium_httpsRedirect");
        testExtensionWithSelenium(fixturesRule.getFixtures().httpsRedirect());
    }

    protected abstract ReplayManagerTester createTester(Path tempRoot, File harFile);

    private void testExtensionWithSelenium(Fixture fixture) throws Exception {
        ReplayManagerTester tester = createTester(temporaryFolder.getRoot().toPath(), fixture.harFile());
        Multimap<URI, String> results = tester.exercise(new XvfbChromeDriverReplayClient(fixture.startUrl()), ReplayManagerTester.findOpenPort());
        assertEquals("results map size", 1, results.size());
        String pageSource = results.values().iterator().next();
        System.out.println(StringUtils.abbreviate(pageSource, 256));
        assertTrue("expected page source to contain title '" + fixture.title() + "'", pageSource.contains(fixture.title()));
    }

    private class XvfbChromeDriverReplayClient implements ReplayClient<Multimap<URI, String>> {

        private final ImmutableList<URI> urisToGet;

        public XvfbChromeDriverReplayClient(URI firstUriToGet, URI... otherUrisToGet) {
            super();
            this.urisToGet = ImmutableList.copyOf(Lists.asList(firstUriToGet, otherUrisToGet));
        }

        @Override
        public Multimap<URI, String> useReplayServer(Path tempDir, HostAndPort proxy, ReplaySessionControl sessionControl) throws Exception {
            ChromeOptions options = ChromeOptionsProducer.getDefault(tempDir).produceOptions(proxy);
            ChromeDriverService service = new ChromeDriverService.Builder()
                    .usingAnyFreePort()
                    .withEnvironment(xvfb.getController().newEnvironment())
                    .build();
            ChromeDriver driver = new ChromeDriver(service, options);
            chromeDriverInstances.add(driver);
            try {
                Multimap<URI, String> pageSources = ArrayListMultimap.create();
                for (URI uri : urisToGet) {
                    driver.get(uri.toString());
                    String pageSource = driver.getPageSource();
                    pageSources.put(uri, pageSource);
                }
                return pageSources;
            } finally {
                driver.quit();
                chromeDriverInstances.remove(driver);
            }
        }
    }

}
