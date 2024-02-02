package io.jenkins.docker.credentials;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Item;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import java.util.List;
import jenkins.authentication.tokens.api.AuthenticationTokens;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.docker.commons.credentials.DockerServerDomainRequirement;
import org.jenkinsci.plugins.docker.commons.credentials.KeyMaterialFactory;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

/**
 * Represents remote (running) container
 *
 * @author magnayn
 */
public class DockerRegistryCredentials extends BaseStandardCredentials {

  @CheckForNull
  private final String username;
  @CheckForNull
  private final Secret password;
  @CheckForNull
  private final String registryUrl;

  @CheckForNull
  private final String email;

  @DataBoundConstructor
  public DockerRegistryCredentials(CredentialsScope scope, String id,
      String description, String username, Secret password, String registryUrl, String email) {
    super(scope, id, description);
    this.username = username;
    this.password = password;
    this.registryUrl = registryUrl;
    this.email = email;
  }

  @NonNull
  public Secret getPassword() {
    return password;
  }

  @NonNull
  public String getUsername() {
    return username;
  }

  @NonNull
  public String getRegistryUrl() {
    return registryUrl;
  }

  @CheckForNull
  public String getEmail() {
    return email;
  }

  @Extension
  @Symbol("dockerRegistryPullCredentials")
  public static class DescriptorImpl extends BaseStandardCredentialsDescriptor {

    @NonNull
    @Override
    public String getDisplayName() {
      return "Docker registry pull credentials";
    }

    public ListBoxModel doFillRegistryCredentialsIdItems(@AncestorInPath Item item, @QueryParameter String uri) {
      if (item == null && !Jenkins.get().hasPermission(Jenkins.ADMINISTER) ||
          item != null && !item.hasPermission(Item.EXTENDED_READ)) {
        return new StandardListBoxModel();
      }
      List<DomainRequirement> domainRequirements = URIRequirementBuilder.fromUri(uri).build();
      domainRequirements.add(new DockerServerDomainRequirement());
      return new StandardListBoxModel()
          .withEmptySelection()
          .withMatching(
              AuthenticationTokens.matcher(KeyMaterialFactory.class),
              CredentialsProvider
                  .lookupCredentials(DockerRegistryCredentials.class, item, null, domainRequirements)
          );
    }

  }
}
