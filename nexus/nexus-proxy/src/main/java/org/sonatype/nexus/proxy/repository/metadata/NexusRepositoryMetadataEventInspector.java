package org.sonatype.nexus.proxy.repository.metadata;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.AbstractLogEnabled;
import org.sonatype.nexus.proxy.events.AbstractEvent;
import org.sonatype.nexus.proxy.events.EventInspector;
import org.sonatype.nexus.proxy.events.RepositoryRegistryEventAdd;
import org.sonatype.nexus.proxy.events.RepositoryRegistryEventUpdate;
import org.sonatype.nexus.proxy.events.RepositoryRegistryRepositoryEvent;
import org.sonatype.nexus.proxy.item.ByteArrayContentLocator;
import org.sonatype.nexus.proxy.item.ContentGenerator;
import org.sonatype.nexus.proxy.item.DefaultStorageFileItem;
import org.sonatype.nexus.proxy.maven.MavenRepository;
import org.sonatype.nexus.proxy.registry.ContentClass;
import org.sonatype.nexus.proxy.repository.GroupRepository;
import org.sonatype.nexus.proxy.repository.ProxyRepository;
import org.sonatype.nexus.proxy.repository.Repository;
import org.sonatype.nexus.repository.metadata.MetadadaHandlerException;
import org.sonatype.nexus.repository.metadata.RepositoryMetadataHandler;
import org.sonatype.nexus.repository.metadata.model.RepositoryMemberMetadata;
import org.sonatype.nexus.repository.metadata.model.RepositoryMetadata;

@Component( role = EventInspector.class, hint = "NexusRepositoryMetadataEventInspector" )
public class NexusRepositoryMetadataEventInspector
    extends AbstractLogEnabled
    implements EventInspector
{
    @Requirement( hint = "maven1" )
    private ContentClass maven1ContentClass;

    @Requirement( hint = "maven2" )
    private ContentClass maven2ContentClass;

    @Requirement
    private RepositoryMetadataHandler repositoryMetadataHandler;

    public boolean accepts( AbstractEvent evt )
    {
        return ( evt instanceof RepositoryRegistryEventAdd ) || ( evt instanceof RepositoryRegistryEventUpdate );
    }

    public void inspect( AbstractEvent evt )
    {
        RepositoryRegistryRepositoryEvent revt = (RepositoryRegistryRepositoryEvent) evt;

        if ( revt.getRepository().getRepositoryContentClass().isCompatible( maven2ContentClass )
            || revt.getRepository().getRepositoryContentClass().isCompatible( maven1ContentClass ) )
        {
            Repository repository = revt.getRepository();

            String repositoryUrl = null;

            String repositoryLocalUrl = null;

            if ( repository.getRepositoryKind().isFacetAvailable( GroupRepository.class ) )
            {
                repositoryUrl = getRepositoryLocalUrl( repository );

                repositoryLocalUrl = null;
            }
            else if ( repository.getRepositoryKind().isFacetAvailable( MavenRepository.class ) )
            {
                // this is a maven repository
                MavenRepository mrepository = revt.getRepository().adaptToFacet( MavenRepository.class );

                if ( mrepository.getRepositoryKind().isFacetAvailable( ProxyRepository.class ) )
                {
                    repositoryUrl = mrepository.adaptToFacet( ProxyRepository.class ).getRemoteUrl();

                    repositoryLocalUrl = getRepositoryLocalUrl( mrepository );
                }
                else
                {
                    repositoryUrl = getRepositoryLocalUrl( mrepository );

                    repositoryLocalUrl = null;
                }
            }
            else
            {
                // huh? unknown stuff, better to not tamper with it
                return;
            }

            RepositoryMetadata rm = repositoryMetadataHandler.createMetadata(
                repositoryUrl,
                repository.getId(),
                repository.getName(),
                repository.getRepositoryContentClass().getId(),
                getRepositoryPolicy( repository ) );

            if ( repositoryLocalUrl != null )
            {
                rm.setLocalUrl( repositoryLocalUrl );
            }

            if ( repository.getRepositoryKind().isFacetAvailable( GroupRepository.class ) )
            {
                List<Repository> members = repository.adaptToFacet( GroupRepository.class ).getMemberRepositories();

                List<RepositoryMemberMetadata> memberMetadatas = new ArrayList<RepositoryMemberMetadata>( members
                    .size() );

                for ( Repository member : members )
                {
                    RepositoryMemberMetadata memberMetadata = new RepositoryMemberMetadata();

                    memberMetadata.setId( member.getId() );

                    memberMetadata.setName( member.getName() );

                    memberMetadata.setUrl( getRepositoryLocalUrl( member ) );

                    memberMetadata.setPolicy( getRepositoryPolicy( repository ) );

                    memberMetadatas.add( memberMetadata );
                }

                rm.getMemberRepositories().addAll( memberMetadatas );
            }

            try
            {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();

                repositoryMetadataHandler.writeRepositoryMetadata( bos, rm );

                DefaultStorageFileItem file = new DefaultStorageFileItem(
                    repository,
                    RepositoryMetadataHandler.REPOSITORY_METADATA_PATH,
                    true,
                    true,
                    new ByteArrayContentLocator( bos.toByteArray() ) );

                file.getAttributes().put(
                    ContentGenerator.CONTENT_GENERATOR_ID,
                    "NexusRepositoryMetadataContentGenerator" );

                repository.storeItem( file );
            }
            catch ( MetadadaHandlerException e )
            {
                getLogger().info( "Could not write repository metadata!", e );
            }
            catch ( IOException e )
            {
                getLogger().warn( "IOException during write of repository metadata!", e );
            }
            catch ( Exception e )
            {
                getLogger().info( "Could not save repository metadata: ", e );
            }
        }
    }

    protected String getRepositoryLocalUrl( Repository repository )
    {
        if ( repository.getRepositoryKind().isFacetAvailable( GroupRepository.class ) )
        {
            return "@rootUrl@/content/groups/" + repository.getId();
        }
        else
        {
            return "@rootUrl@/content/repositories/" + repository.getId();
        }
    }

    protected String getRepositoryPolicy( Repository repository )
    {
        if ( repository.getRepositoryKind().isFacetAvailable( MavenRepository.class ) )
        {
            return repository.adaptToFacet( MavenRepository.class ).getRepositoryPolicy().toString().toLowerCase();
        }
        else if ( repository.getRepositoryKind().isFacetAvailable( GroupRepository.class ) )
        {
            List<Repository> members = repository.adaptToFacet( GroupRepository.class ).getMemberRepositories();

            HashSet<String> memberPolicies = new HashSet<String>();

            for ( Repository member : members )
            {
                memberPolicies.add( getRepositoryPolicy( member ) );
            }

            if ( memberPolicies.size() == 1 )
            {
                return memberPolicies.iterator().next();
            }
            else
            {
                return RepositoryMetadata.POLICY_MIXED;
            }
        }
        else
        {
            return RepositoryMetadata.POLICY_MIXED;
        }
    }

}
