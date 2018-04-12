package io.github.mike10004.harreplay.vhsimpl;

import io.github.mike10004.harreplay.ReplayManager;
import io.github.mike10004.harreplay.tests.ReadmeExample;

import java.io.File;

public class Examples {

    public static class ApacheHttpClientExample extends ReadmeExample {

        public static void main(String[] args) throws Exception {
            File harFile = new File(ReadmeExample.class.getResource("/http.www.example.com.har").toURI());
            new ApacheHttpClientExample().execute(harFile);
        }

        @Override
        protected ReplayManager createReplayManager() {
            VhsReplayManagerConfig config = VhsReplayManagerConfig.getDefault();
            return new VhsReplayManager(config);
        }
    }
}
