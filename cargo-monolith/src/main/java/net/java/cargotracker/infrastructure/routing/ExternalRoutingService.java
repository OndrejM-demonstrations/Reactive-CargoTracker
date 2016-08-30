package net.java.cargotracker.infrastructure.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.ejb.Stateless;
import javax.enterprise.concurrent.ContextService;
import javax.enterprise.concurrent.ManagedExecutorService;
import javax.inject.Inject;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import net.java.cargotracker.application.util.JsonMoxyConfigurationContextResolver;
import net.java.cargotracker.application.util.reactive.JaxrsResponseCallback;
import net.java.cargotracker.domain.model.cargo.Itinerary;
import net.java.cargotracker.domain.model.cargo.Leg;
import net.java.cargotracker.domain.model.cargo.RouteSpecification;
import net.java.cargotracker.domain.model.location.LocationRepository;
import net.java.cargotracker.domain.model.location.UnLocode;
import net.java.cargotracker.domain.model.voyage.VoyageNumber;
import net.java.cargotracker.domain.model.voyage.VoyageRepository;
import net.java.cargotracker.domain.service.RoutingService;
import org.glassfish.jersey.moxy.json.MoxyJsonFeature;

/**
 * Our end of the routing service. This is basically a data model translation
 * layer between our domain model and the API put forward by the routing team,
 * which operates in a different context from us.
 *
 */
@Stateless
public class ExternalRoutingService implements RoutingService {

    // a URL retrieved from an externally configured JNDI entry
//    @Resource(lookup = "graphTraversalUrlJNDI")
    private String graphTraversalUrlJNDI;

    // the ejb entry URL
    @Resource(name = "graphTraversalUrl")
    private String graphTraversalUrl;

    // TODO Can I use injection?
    private final Client jaxrsClient = ClientBuilder.newClient();
    private WebTarget graphTraversalResource;
    @Inject
    private LocationRepository locationRepository;
    @Inject
    private VoyageRepository voyageRepository;
    // TODO Use injection instead?
    private static final Logger log = Logger.getLogger(
            ExternalRoutingService.class.getName());

    @Resource
    ManagedExecutorService executor;

    @Resource
    ContextService ctxService;

    @PostConstruct
    public void init() {
        // if we have an explictly configured external JNDI entry use that
        if (graphTraversalUrlJNDI != null) {
            graphTraversalUrl = graphTraversalUrlJNDI;
        }
        graphTraversalResource = jaxrsClient.target(graphTraversalUrl);
        graphTraversalResource.register(new MoxyJsonFeature()).register(
                new JsonMoxyConfigurationContextResolver());
    }

    @Override
    public CompletionStage<List<Itinerary>> fetchRoutesForSpecification(
            RouteSpecification routeSpecification) {
        // The RouteSpecification is picked apart and adapted to the external API.
        String origin = routeSpecification.getOrigin().getUnLocode().getIdString();
        String destination = routeSpecification.getDestination().getUnLocode()
                .getIdString();

        Consumer<Runnable> asyncContext = ThreadAsyncContext.create(ctxService);
        
        return JaxrsResponseCallback.get(
                graphTraversalResource
                .queryParam("origin", origin)
                .queryParam("destination", destination)
                .request(MediaType.APPLICATION_JSON_TYPE)
                .async())
                .thenApply(r -> {

                    // The returned result is then translated back into our domain model.
                    List<Itinerary> itineraries = new ArrayList<>();

                    asyncContext.accept( () -> {
                        List<TransitPath> transitPaths = r.readEntity(new GenericType<List<TransitPath>>() {
                        });

                        for (TransitPath transitPath : transitPaths) {
                            Itinerary itinerary = toItinerary(transitPath);
                            // Use the specification to safe-guard against invalid itineraries
                            if (routeSpecification.isSatisfiedBy(itinerary)) {
                                itineraries.add(itinerary);
                            } else {
                                log.log(Level.FINE,
                                        "Received itinerary that did not satisfy the route specification");
                            }
                        }
                    });
                    

                    return itineraries;
                });

    }
    
    private static class ThreadAsyncContext implements Consumer<Runnable> {
        public static Consumer<Runnable> create(ContextService ctxService) {
            return ctxService.createContextualProxy(new ThreadAsyncContext(), Consumer.class);
        }

        @Override
        public void accept(Runnable r) {
            r.run();
        }

    }

    private Itinerary toItinerary(TransitPath transitPath) {
        List<Leg> legs = new ArrayList<>(transitPath.getTransitEdges().size());
        for (TransitEdge edge : transitPath.getTransitEdges()) {
            legs.add(toLeg(edge));
        }
        return new Itinerary(legs);
    }

    private Leg toLeg(TransitEdge edge) {
        return new Leg(
                voyageRepository.find(new VoyageNumber(edge.getVoyageNumber())),
                locationRepository.find(new UnLocode(edge.getFromUnLocode())),
                locationRepository.find(new UnLocode(edge.getToUnLocode())),
                edge.getFromDate(), edge.getToDate());
    }
}
