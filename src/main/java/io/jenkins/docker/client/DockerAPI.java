package io.jenkins.docker.client;

import static com.cloudbees.plugins.credentials.CredentialsMatchers.firstOrNull;
import static com.cloudbees.plugins.credentials.CredentialsMatchers.withId;
import static com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentials;
import static com.nirima.jenkins.plugins.docker.utils.JenkinsUtils.bldToString;
import static com.nirima.jenkins.plugins.docker.utils.JenkinsUtils.endToString;
import static com.nirima.jenkins.plugins.docker.utils.JenkinsUtils.startToString;
import static org.apache.commons.lang.StringUtils.trimToNull;

import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.VersionCmd;
import com.github.dockerjava.api.model.Version;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.core.SSLConfig;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.jenkins.dockerjavaapi.client.DelegatingDockerClient;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.docker.commons.credentials.DockerServerCredentials;
import org.jenkinsci.plugins.docker.commons.credentials.DockerServerEndpoint;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class DockerAPI extends AbstractDescribableImpl<DockerAPI> {
    private static final Logger LOGGER = LoggerFactory.getLogger(DockerAPI.class);

    private DockerServerEndpoint dockerHost;

    /** Connection timeout in seconds */
    private int connectTimeout;

    /** Read timeout in seconds */
    private int readTimeout;

    private String apiVersion;

    private String hostname;
    private String registryCredentialsId;
    private String registryUrl;

    /**
     * Is this host actually a swarm?
     */
    private transient Boolean _isSwarm;

    @DataBoundConstructor
    public DockerAPI(DockerServerEndpoint dockerHost) {
        this.dockerHost = dockerHost;
    }

    public DockerAPI(DockerServerEndpoint dockerHost, String registryCredentialsId, String registryUrl) {
        this.dockerHost = dockerHost;
        this.registryUrl = registryUrl;
        this.registryCredentialsId = registryCredentialsId;
    }

    public DockerAPI(
            DockerServerEndpoint dockerHost, int connectTimeout, int readTimeout, String apiVersion, String hostname) {
        this.dockerHost = dockerHost;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.apiVersion = apiVersion;
        this.hostname = hostname;
    }

    public DockerServerEndpoint getDockerHost() {
        return dockerHost;
    }

    public int getConnectTimeout() {
        return connectTimeout;
    }

    @DataBoundSetter
    public void setConnectTimeout(int connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public int getReadTimeout() {
        return readTimeout;
    }

    @DataBoundSetter
    public void setReadTimeout(int readTimeout) {
        this.readTimeout = readTimeout;
    }

    public String getApiVersion() {
        return apiVersion;
    }

    @DataBoundSetter
    public void setApiVersion(String apiVersion) {
        this.apiVersion = trimToNull(apiVersion);
    }

    public String getHostname() {
        return hostname;
    }

    @DataBoundSetter
    public void setHostname(String hostname) {
        this.hostname = trimToNull(hostname);
    }

    public boolean isSwarm() {
        if (_isSwarm == null) {
            try (final DockerClient client = getClient()) {
                Version remoteVersion = client.versionCmd().exec();
                // Cache the return.
                _isSwarm = remoteVersion.getVersion().startsWith("swarm");
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
        return _isSwarm;
    }

    /**
     * Obtains a raw {@link DockerClient} pointing at our docker service
     * endpoint. You <em>MUST</em> ensure that you call
     * {@link Closeable#close()} on the returned instance after you are finished
     * with it, otherwise we will leak resources.
     * <p>
     * Note: {@link DockerClient}s are cached and shared between threads, so
     * taking and releasing is relatively cheap. They're not closed "for real"
     * until they've been unused for some time.
     * </p>
     *
     * @return A raw {@link DockerClient} pointing at our docker service
     *         endpoint.
     */
    public DockerClient getClient() {
        return getClient(readTimeout);
    }

    /**
     * As {@link #getClient()}, but overriding the default
     * <code>readTimeout</code>. This is typically used when running
     * long-duration activities that can "go quiet" for a long period of time,
     * e.g. pulling a docker image from a registry or building a docker image.
     * Most users should just call {@link #getClient()} instead.
     *
     * @param activityTimeoutInSeconds
     *            The activity timeout, in seconds. A value less than one means
     *            no timeout.
     * @return A raw {@link DockerClient} pointing at our docker service
     *         endpoint.
     */
    public DockerClient getClient(int activityTimeoutInSeconds) {
        return getOrMakeClient(
                dockerHost.getUri(),
                dockerHost.getCredentialsId(),
                activityTimeoutInSeconds,
                connectTimeout,
                registryCredentialsId,
                registryUrl);
    }

    /** Caches connections until they've been unused for 5 minutes */
    private static final UsageTrackingCache<DockerClientParameters, SharableDockerClient> CLIENT_CACHE;

    static {
        final UsageTrackingCache.ExpiryHandler<DockerClientParameters, SharableDockerClient> expiryHandler;
        expiryHandler = new UsageTrackingCache.ExpiryHandler<>() {
            @Override
            public void entryDroppedFromCache(DockerClientParameters cacheKey, SharableDockerClient client) {
                try {
                    client.reallyClose();
                    LOGGER.info("Dro pped connection {} to {}", client, cacheKey);
                } catch (IOException ex) {
                    LOGGER.error("Dropped connection " + client + " to " + cacheKey + " but failed to close it:", ex);
                }
            }
        };
        CLIENT_CACHE = new UsageTrackingCache(5, TimeUnit.MINUTES, expiryHandler);
    }

    /** Obtains a {@link DockerClient} from the cache, or makes one and puts it in the cache, implicitly telling the cache we need it. */
    private static DockerClient getOrMakeClient(
            final String dockerUri,
            final String credentialsId,
            final int readTimeout,
            final int connectTimeout,
            final String registryCredentialsId,
            final String registryUrl) {
        final Integer readTimeoutInMillisecondsOrNull = readTimeout > 0 ? readTimeout * 1000 : null;
        final Integer connectTimeoutInMillisecondsOrNull = connectTimeout > 0 ? connectTimeout * 1000 : null;
        final DockerClientParameters cacheKey = new DockerClientParameters(
                dockerUri,
                credentialsId,
                readTimeoutInMillisecondsOrNull,
                connectTimeoutInMillisecondsOrNull,
                registryCredentialsId,
                registryUrl);
        synchronized (CLIENT_CACHE) {
            SharableDockerClient client = CLIENT_CACHE.getAndIncrementUsage(cacheKey);
            if (client == null) {
                client = makeClient(
                        dockerUri,
                        credentialsId,
                        readTimeoutInMillisecondsOrNull,
                        connectTimeoutInMillisecondsOrNull,
                        registryCredentialsId,
                        registryUrl);
                LOGGER.info("Cached connection {} to {}", client, cacheKey);
                CLIENT_CACHE.cacheAndIncrementUsage(cacheKey, client);
            }
            return client;
        }
    }

    /**
     * A docker-client that, when {@link Closeable#close()} is called, merely
     * decrements the usage count. It'll only get properly closed once it's
     * purged from the cache.
     */
    private static class SharableDockerClient extends DelegatingDockerClient {

        public SharableDockerClient(DockerClient delegate) {
            super(delegate);
        }

        /**
         * Tell the cache we no longer need the {@link DockerClient} and it can
         * be thrown away if it remains unused.
         */
        @Override
        public void close() {
            synchronized (CLIENT_CACHE) {
                CLIENT_CACHE.decrementUsage(this);
            }
        }

        /**
         * Really closes the underlying {@link DockerClient}.
         */
        public void reallyClose() throws IOException {
            getDelegate().close();
        }
    }

    /**
     * Creates a new {@link DockerClient}.
     * It's the caller's responsibility to dispose of the result.
     */
    @SuppressWarnings("resource")
    private static SharableDockerClient makeClient(
            final String dockerUri,
            final String credentialsId,
            final Integer readTimeoutInMillisecondsOrNull,
            final Integer connectTimeoutInMillisecondsOrNull,
            final String registryCredentialsId,
            final String registryUrl) {
        DockerHttpClient httpClient = null;
        DockerClient actualClient = null;
        try {
            httpClient = new ApacheDockerHttpClient.Builder() //
                    .dockerHost(URI.create(dockerUri)) //
                    .sslConfig(toSSlConfig(credentialsId)) //
                    .connectionTimeout(
                            connectTimeoutInMillisecondsOrNull != null
                                    ? Duration.ofMillis(connectTimeoutInMillisecondsOrNull.intValue())
                                    : null) //
                    .responseTimeout(
                            readTimeoutInMillisecondsOrNull != null
                                    ? Duration.ofMillis(readTimeoutInMillisecondsOrNull.intValue())
                                    : null) //
                    .build();

            if (registryCredentialsId != null && registryUrl != null) {
                StandardUsernamePasswordCredentials creds = getUsername(registryCredentialsId);
                DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                        .withRegistryUsername(creds.getUsername())
                        .withRegistryPassword(creds.getPassword().getPlainText())
                        .withRegistryEmail("test@bics.com")
                        .withRegistryUrl(registryUrl)
                        .build();
                actualClient = DockerClientImpl.getInstance(config, httpClient);
            } else {
                actualClient = DockerClientBuilder.getInstance()
                        .withDockerHttpClient(httpClient)
                        .build();
            }
            final SharableDockerClient multiUsageClient = new SharableDockerClient(actualClient);
            // if we've got this far, we're going to succeed, so we need to ensure that we
            // don't close the resources we're returning.
            httpClient = null;
            actualClient = null;
            return multiUsageClient;
        } finally {
            // these will no-op if we're successfully returning a value, but in any error
            // cases we should ensure that we don't leak precious resources.
            closeAndLogAnyExceptions(httpClient);
            closeAndLogAnyExceptions(actualClient);
        }
    }

    private static void closeAndLogAnyExceptions(Closeable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception ex) {
                LOGGER.error("Unable to close {}", resource.toString(), ex);
            }
        }
    }

    private static SSLConfig toSSlConfig(String credentialsId) {
        if (credentialsId == null) {
            return null;
        }

        DockerServerCredentials credentials = firstOrNull(
                lookupCredentials(DockerServerCredentials.class, Jenkins.get(), ACL.SYSTEM, List.of()),
                withId(credentialsId));
        return credentials == null ? null : new DockerServerCredentialsSSLConfig(credentials);
    }

    private static StandardUsernamePasswordCredentials getUsername(String credentialsId) {
        return firstOrNull(
                lookupCredentials(StandardUsernamePasswordCredentials.class, Jenkins.get(), ACL.SYSTEM, List.of()),
                withId(credentialsId));
    }

    /**
     * Create a plain {@link Socket} to docker API endpoint
     *
     * @return The {@link Socket} direct to the docker daemon.
     * @throws IOException if anything goes wrong.
     */
    public Socket getSocket() throws IOException {
        try {
            final URI uri = new URI(dockerHost.getUri());
            if ("unix".equals(uri.getScheme())) {
                final String socketFileName = uri.getPath();
                final AFUNIXSocketAddress unix = AFUNIXSocketAddress.of(new File(socketFileName));
                final Socket socket = AFUNIXSocket.newInstance();
                socket.connect(unix);
                return socket;
            }

            final SSLConfig sslConfig = toSSlConfig(dockerHost.getCredentialsId());
            if (sslConfig != null) {
                return sslConfig.getSSLContext().getSocketFactory().createSocket(uri.getHost(), uri.getPort());
            }
            return new Socket(uri.getHost(), uri.getPort());
        } catch (Exception e) {
            throw new IOException("Failed to create a Socker for docker URI " + dockerHost.getUri(), e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        DockerAPI dockerAPI = (DockerAPI) o;

        if (connectTimeout != dockerAPI.connectTimeout) {
            return false;
        }
        if (readTimeout != dockerAPI.readTimeout) {
            return false;
        }
        if (!Objects.equals(dockerHost, dockerAPI.dockerHost)) {
            return false;
        }
        if (!Objects.equals(apiVersion, dockerAPI.apiVersion)) {
            return false;
        }
        if (!Objects.equals(hostname, dockerAPI.hostname)) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = dockerHost != null ? dockerHost.hashCode() : 0;
        result = 31 * result + connectTimeout;
        result = 31 * result + readTimeout;
        result = 31 * result + (apiVersion != null ? apiVersion.hashCode() : 0);
        result = 31 * result + (hostname != null ? hostname.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        final StringBuilder sb = startToString(this);
        bldToString(sb, "dockerHost", dockerHost);
        bldToString(sb, "connectTimeout", connectTimeout);
        bldToString(sb, "readTimeout", readTimeout);
        bldToString(sb, "apiVersion", apiVersion);
        bldToString(sb, "hostname", hostname);
        endToString(sb);
        return sb.toString();
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<DockerAPI> {

        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item context, @QueryParameter String uri) {
            final DockerServerEndpoint.DescriptorImpl descriptor =
                    (DockerServerEndpoint.DescriptorImpl) Jenkins.get().getDescriptorOrDie(DockerServerEndpoint.class);
            return descriptor.doFillCredentialsIdItems(context, uri);
        }

        public FormValidation doCheckCredentialsId(
                @AncestorInPath Item context, @QueryParameter String uri, @QueryParameter String value) {
            final String credentialsOrNull = trimToNull(value);
            if (credentialsOrNull == null || credentialsAreValid(context, uri, credentialsOrNull)) {
                return FormValidation.ok();
            }
            return FormValidation.error("Invalid credentials for URI " + uri);
        }

        public FormValidation doCheckConnectionTimeout(@QueryParameter String value) {
            return FormValidation.validateNonNegativeInteger(value);
        }

        public FormValidation doCheckReadTimeout(@QueryParameter String value) {
            return FormValidation.validateNonNegativeInteger(value);
        }

        @RequirePOST
        public FormValidation doTestConnection(
                @AncestorInPath Item context,
                @QueryParameter String uri,
                @QueryParameter String credentialsId,
                @QueryParameter String apiVersion,
                @QueryParameter int connectTimeout,
                @QueryParameter int readTimeout) {
            throwIfNoPermission(context);
            final FormValidation credentialsIdCheckResult = doCheckCredentialsId(context, uri, credentialsId);
            if (credentialsIdCheckResult != FormValidation.ok()) {
                return FormValidation.error("Invalid credentials");
            }
            try {
                final DockerServerEndpoint dsep = new DockerServerEndpoint(uri, credentialsId);
                final DockerAPI dapi = new DockerAPI(dsep, connectTimeout, readTimeout, apiVersion, null);
                try (final DockerClient dc = dapi.getClient()) {
                    final VersionCmd vc = dc.versionCmd();
                    final Version v = vc.exec();
                    final String actualVersion = v.getVersion();
                    final String actualApiVersion = v.getApiVersion();
                    return FormValidation.ok("Version = " + actualVersion + ", API Version = " + actualApiVersion);
                }
            } catch (Exception e) {
                return FormValidation.error(e, e.getMessage());
            }
        }

        private boolean credentialsAreValid(Item context, String uri, final String credentialsId) {
            final ListBoxModel availableCredentials = doFillCredentialsIdItems(context, uri);
            return optionIsAvailable(credentialsId, availableCredentials);
        }

        private static boolean optionIsAvailable(final String optionValue, final ListBoxModel available) {
            for (ListBoxModel.Option o : available) {
                if (o.value == null) {
                    if (optionValue == null) {
                        return true; // both null = match
                    }
                } else {
                    if (optionValue != null && optionValue.equals(o.value)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private static void throwIfNoPermission(Item context) {
            if (context != null) {
                context.checkPermission(Item.CONFIGURE);
            } else {
                Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            }
        }
    }
}
