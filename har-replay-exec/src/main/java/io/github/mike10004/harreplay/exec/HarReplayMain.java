package io.github.mike10004.harreplay.exec;

import com.github.mike10004.nativehelper.subprocess.ProcessMonitor;
import com.github.mike10004.nativehelper.subprocess.ScopedProcessTracker;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.CharSource;
import com.google.common.io.Files;
import com.google.common.net.HostAndPort;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.gson.Gson;
import de.sstoehr.harreader.HarReader;
import de.sstoehr.harreader.HarReaderException;
import de.sstoehr.harreader.HarReaderMode;
import de.sstoehr.harreader.model.Har;
import de.sstoehr.harreader.model.HarEntry;
import io.github.mike10004.harreplay.ReplayManager;
import io.github.mike10004.harreplay.ReplayServerConfig;
import io.github.mike10004.harreplay.ReplaySessionConfig;
import io.github.mike10004.harreplay.ReplaySessionControl;
import io.github.mike10004.harreplay.exec.ChromeBrowserSupport.OutputDestination;
import io.github.mike10004.harreplay.exec.ChromeBrowserSupport.SwitcherooMode;
import io.github.mike10004.harreplay.exec.HarInfoDumper.SummaryDumper;
import io.github.mike10004.harreplay.exec.HarInfoDumper.TerseDumper;
import io.github.mike10004.harreplay.exec.HarInfoDumper.VerboseDumper;
import io.github.mike10004.harreplay.nodeimpl.NodeServerReplayManager;
import io.github.mike10004.harreplay.nodeimpl.NodeServerReplayManagerConfig;
import io.github.mike10004.harreplay.vhsimpl.VhsReplayManager;
import io.github.mike10004.harreplay.vhsimpl.VhsReplayManagerConfig;
import joptsimple.NonOptionArgumentSpec;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.net.ServerSocket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

public class HarReplayMain {

    private static final Logger log = LoggerFactory.getLogger(HarReplayMain.class);

    static final String OPT_NOTIFY = "notify";
    static final String OPT_SCRATCH_DIR = "scratch-dir";
    static final String OPT_PORT = "port";
    static final String OPT_BROWSER = "browser";
    static final String OPT_ECHO_SERVER = "echo-server";
    static final String OPT_ENGINE = "engine";
    static final String OPT_REPLAY_CONFIG = "config";
    static final String OPT_SWITCHEROO = "switcheroo";
    static final String OPT_ECHO_BROWSER_OUTPUT = "echo-browser-output";
    static final Charset NOTIFY_FILE_CHARSET = StandardCharsets.US_ASCII;

    private final OptionParser parser;
    private final OptionSpec<File> notifySpec;
    private final NonOptionArgumentSpec<File> harFileSpec;
    private final OptionSpec<Integer> portSpec;
    private final OptionSpec<File> scratchDirSpec;
    private final OptionSpec<Void> helpSpec;
    private final OptionSpec<Browser> browserSpec;
    private final OptionSpec<HarDumpStyle> harDumpStyleSpec;
    private final OptionSpec<Void> echoServerSpec;
    private final OptionSpec<ReplayServerEngine> engineSpec;
    private final OptionSpec<File> replayConfigSpec;

    public HarReplayMain() {
        this(new OptionParser());
    }

    @VisibleForTesting
    HarReplayMain(OptionParser parser) throws UsageException {
        this.parser = requireNonNull(parser, "parser");
        parser.formatHelpWith(new CustomHelpFormatter());
        helpSpec = parser.acceptsAll(Arrays.asList("h", "help"), "print help and exit").forHelp();
        harFileSpec = parser.nonOptions("har file").ofType(File.class).describedAs("FILE");
        notifySpec = parser.accepts(OPT_NOTIFY, "notify that server is up by printing listening port to file")
                .withRequiredArg().ofType(File.class);
        portSpec = parser.acceptsAll(Arrays.asList("p", OPT_PORT), "port to listen on")
                .withRequiredArg().ofType(Integer.class)
                .describedAs("PORT");
        scratchDirSpec = parser.acceptsAll(Arrays.asList("d", OPT_SCRATCH_DIR), "scratch directory to use")
                .withRequiredArg().ofType(File.class)
                .describedAs("DIRNAME");
        browserSpec = parser.acceptsAll(Arrays.asList("b", OPT_BROWSER), "launch browser configured for replay server; only 'chrome' is supported")
                .withRequiredArg().ofType(Browser.class)
                .describedAs("BROWSER");
        harDumpStyleSpec = parser.acceptsAll(Collections.singletonList("dump-har"), "dump har (choices: " + HarDumpStyle.describeChoices() + ")")
                .withRequiredArg().ofType(HarDumpStyle.class)
                .describedAs("STYLE")
                .defaultsTo(HarDumpStyle.summary);
        echoServerSpec = parser.acceptsAll(Collections.singletonList(OPT_ECHO_SERVER), "echo proxy server output");
        engineSpec = parser.acceptsAll(Arrays.asList("e", OPT_ENGINE), "specify replay server engine; ENGINE must be one of " + ReplayServerEngine.describeChoices())
                .withRequiredArg().ofType(ReplayServerEngine.class)
                .describedAs("ENGINE")
                .defaultsTo(ReplayServerEngine.vhs);
        replayConfigSpec = parser.acceptsAll(Arrays.asList("f", OPT_REPLAY_CONFIG), "specify replay config file")
                .withRequiredArg().ofType(File.class);
        parser.accepts(OPT_ECHO_BROWSER_OUTPUT, "with --browser, print browser output to console");
        parser.accepts(OPT_SWITCHEROO, "with --browser=chrome, use extension to change https URLs to http");
    }

    protected List<HarEntry> readHarEntries(File harFile, Path scratchDir) throws IOException, HarReaderException {
        CharSource cleanSource = SstoehrHarCleaningTransform.onDisk(scratchDir).transform(Files.asCharSource(harFile, StandardCharsets.UTF_8));
        Har har = new HarReader().readFromString(cleanSource.read(), HarReaderMode.LAX);
        return har.getLog().getEntries();
    }

    int main0(String[] args) throws IOException {
        try {
            OptionSet optionSet = parser.parse(args);
            if (optionSet.has(helpSpec)) {
                parser.printHelpOn(System.out);
                return 0;
            }
            ReplayServerEngine engine = optionSet.valueOf(engineSpec);
            ReplayManager manager = engine.createManager(this, optionSet);
            try (CloseableWrapper<ReplaySessionConfig> sessionConfigWrapper = createReplaySessionConfig(optionSet)) {
                ReplaySessionConfig sessionConfig = sessionConfigWrapper.getWrapped();
                HostAndPort replayServerAddress = HostAndPort.fromParts("localhost", sessionConfig.port);
                try (ReplaySessionControl ignore = manager.start(sessionConfig);
                     ScopedProcessTracker processTracker = new ProcessTrackerWithShutdownHook(Runtime.getRuntime())) {
                    maybeNotify(sessionConfig, optionSet.valueOf(notifySpec));
                    HarDumpStyle harDumpStyle = optionSet.valueOf(harDumpStyleSpec);
                    try {
                        harDumpStyle.getDumper().dump(readHarEntries(sessionConfig.harFile, sessionConfig.scratchDir), System.out);
                    } catch (HarReaderException e) {
                        System.err.format("har-replay: failed to read from har file: %s%n", e.getMessage());
                    }
                    Browser browser = optionSet.valueOf(browserSpec);
                    if (browser != null) {
                        //noinspection unused // TODO: provide an alternate method to initate orderly shutdown using this monitor
                        ProcessMonitor<?, ?> monitor = browser.getSupport(optionSet)
                                .prepare(sessionConfig.scratchDir)
                                .launch(replayServerAddress, processTracker);
                    }

                    sleepForever();
                }
            }
        } catch (UsageException e) {
            System.err.format("har-replay: %s%n", e.getMessage());
            System.err.format("har-replay: use --help to print options");
            return 1;
        }
        return 0;
    }

    protected void sleepForever() {
        Uninterruptibles.sleepUninterruptibly(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
    }

    protected void maybeNotify(ReplaySessionConfig sessionConfig, @Nullable File notifyFile) throws IOException {
        if (notifyFile != null) {
            Files.asCharSink(notifyFile, NOTIFY_FILE_CHARSET).write(String.valueOf(sessionConfig.port));
        }
    }

    protected int findUnusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    protected CloseableWrapper<ReplaySessionConfig> createReplaySessionConfig(OptionSet optionSet) throws IOException {
        File scratchDir = optionSet.valueOf(scratchDirSpec);
        List<Runnable> cleanups = new ArrayList<>();
        if (scratchDir == null) {
            Path scratchDirPath = java.nio.file.Files.createTempDirectory("har-replay-temporary");
            cleanups.add(() -> {
                try {
                    FileUtils.forceDelete(scratchDirPath.toFile());
                } catch (IOException e) {
                    if (scratchDirPath.toFile().exists()) {
                        log.warn("failed to delete scratch directory " + scratchDirPath, e);
                    }
                }
            });
            scratchDir = scratchDirPath.toFile();
        }
        Integer port = optionSet.valueOf(portSpec);
        if (port == null) {
            port = findUnusedPort();
        }
        File harFile = optionSet.valueOf(harFileSpec);
        if (harFile == null) {
            throw new UsageException("har file must be specified as positional argument");
        }
        ReplayServerConfig serverConfig = buildReplayServerConfig(optionSet);
        ReplaySessionConfig config = ReplaySessionConfig.builder(scratchDir.toPath())
                .config(serverConfig)
                .port(port)
                .build(harFile);
        return new CloseableWrapper<ReplaySessionConfig>() {
            @Override
            public ReplaySessionConfig getWrapped() {
                return config;
            }

            @Override
            public void close() {
                cleanups.forEach(Runnable::run);
            }
        };
    }

    protected Gson createReplayServerConfigGson() {
        return createDefaultReplayServerConfigGson();
    }

    protected static Gson createDefaultReplayServerConfigGson() {
        return ReplayServerConfig.createSerialist();
    }

    protected ReplayServerConfig buildReplayServerConfig(OptionSet optionSet) throws IOException {
        File replayConfigFile = replayConfigSpec.value(optionSet);
        if (replayConfigFile != null) {
            try (Reader reader = Files.asCharSource(replayConfigFile, StandardCharsets.UTF_8).openStream()) {
                return createReplayServerConfigGson().fromJson(reader, ReplayServerConfig.class);
            }
        } else {
            return ReplayServerConfig.empty();
        }
    }

    @SuppressWarnings("unused")
    private static class UsageException extends RuntimeException {
        public UsageException(String message) {
            super(message);
        }

        public UsageException(String message, Throwable cause) {
            super(message, cause);
        }

        public UsageException(Throwable cause) {
            super(cause);
        }
    }

    public static void main(String[] args) throws Exception {
        int exitCode = new HarReplayMain().main0(args);
        System.exit(exitCode);
    }

    protected interface CloseableWrapper<T> extends Closeable {

        T getWrapped();

    }

    public enum Browser {
        chrome;

        BrowserSupport getSupport(OptionSet options) {
            switch (this) {
                case chrome:
                    return new ChromeBrowserSupport(options.has(OPT_SWITCHEROO) ? SwitcherooMode.ENABLED : SwitcherooMode.NOT_ADDED,
                            options.has(OPT_ECHO_BROWSER_OUTPUT) ? OutputDestination.CONSOLE : OutputDestination.FILES);
            }
            throw new IllegalStateException("not handled: " + this);
        }
    }

    public enum ReplayServerEngine {
        node,
        vhs;

        public static String describeChoices() {
            return "{" + String.join(", ", Stream.of(values()).map(ReplayServerEngine::name).collect(Collectors.toSet())) + "}";
        }

        protected NodeServerReplayManagerConfig createNodeReplayManagerConfig(HarReplayMain main, OptionSet optionSet) {
            NodeServerReplayManagerConfig.Builder b = NodeServerReplayManagerConfig.builder();
            if (optionSet.has(main.echoServerSpec)) {
                b.addOutputEchoes();
            }
            return b.build();
        }

        @SuppressWarnings("unused") // no options to set when creating config instance yet
        protected VhsReplayManagerConfig createVhsReplayManagerConfig(HarReplayMain main, OptionSet optionSet) {
            return VhsReplayManagerConfig.getDefault();
        }

        public ReplayManager createManager(HarReplayMain main, OptionSet optionSet) {
            switch (this) {
                case node:
                    NodeServerReplayManagerConfig nodeConfig = createNodeReplayManagerConfig(main, optionSet);
                    return new NodeServerReplayManager(nodeConfig);
                case vhs:
                    VhsReplayManagerConfig vhsConfig = createVhsReplayManagerConfig(main, optionSet);
                    return new VhsReplayManager(vhsConfig);
                default:
                    throw new IllegalStateException("unhandled: " + this);
            }
        }
    }

    public enum HarDumpStyle {
        silent,
        terse,
        summary,
        verbose;

        HarInfoDumper getDumper() {
            switch (this) {
                case silent: return HarInfoDumper.silent();
                case terse: return new TerseDumper();
                case summary: return new SummaryDumper();
                case verbose: return new VerboseDumper();
            }
            throw new IllegalStateException("not handled: " + this);
        }

        public static String describeChoices() {
            return String.join(", ", Stream.of(values()).map(c -> String.format("'%s'", c.name())).collect(Collectors.toList()));
        }
    }

    private static class ProcessTrackerWithShutdownHook extends ScopedProcessTracker {

        public ProcessTrackerWithShutdownHook(Runtime runtime) {
            addShutdownHook(runtime);
        }

        private void addShutdownHook(Runtime runtime) {
            runtime.addShutdownHook(new Thread(this::destroyAll));
        }
    }

}
