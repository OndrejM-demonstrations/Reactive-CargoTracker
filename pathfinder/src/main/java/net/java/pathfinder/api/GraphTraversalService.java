package net.java.pathfinder.api;

import fish.payara.micro.cdi.Inbound;
import fish.payara.micro.cdi.Outbound;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.Stateless;
import javax.enterprise.event.Event;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.ws.rs.Path;
import net.java.pathfinder.internal.GraphDao;
import net.java.pathfinder.internal.Slow;

@Stateless
@Path("/graph-traversal")
public class GraphTraversalService {

    @Inject
    @Slow
    private GraphDao dao;
    private final Random random = new Random();
    private static final long ONE_MIN_MS = 1000 * 60;
    private static final long ONE_DAY_MS = ONE_MIN_MS * 60 * 24;

    Logger logger = Logger.getLogger(GraphTraversalService.class.getCanonicalName());
    
    @Inject
    @Outbound
    private Event<GraphTraversalResponse> responseEvent;

    public void findShortestPath(@Observes @Inbound GraphTraversalRequest request) {
        String originUnLocode = request.getOrigin();
        String destinationUnLocode = request.getDestination();

        dao.listLocations()
                .thenAccept((List<String> allVertices) -> {
                    Date date = nextDate(new Date());
                    allVertices.remove(originUnLocode);
                    allVertices.remove(destinationUnLocode);

                    int candidateCount = getRandomNumberOfCandidates();
                    List<TransitPath> candidates = new ArrayList<>(
                            candidateCount);

                    for (int i = 0; i < candidateCount; i++) {
                        allVertices = getRandomChunkOfLocations(allVertices);
                        List<TransitEdge> transitEdges = new ArrayList<>(
                                allVertices.size() - 1);
                        String firstLegTo = allVertices.get(0);

                        Date fromDate = nextDate(date);
                        Date toDate = nextDate(fromDate);
                        date = nextDate(toDate);

                        transitEdges.add(new TransitEdge(
                                dao.getVoyageNumber(originUnLocode, firstLegTo),
                                originUnLocode, firstLegTo, fromDate, toDate));

                        for (int j = 0; j < allVertices.size() - 1; j++) {
                            String current = allVertices.get(j);
                            String next = allVertices.get(j + 1);
                            fromDate = nextDate(date);
                            toDate = nextDate(fromDate);
                            date = nextDate(toDate);
                            transitEdges.add(new TransitEdge(dao.getVoyageNumber(current,
                                    next), current, next, fromDate, toDate));
                        }

                        String lastLegFrom = allVertices.get(allVertices.size() - 1);
                        fromDate = nextDate(date);
                        toDate = nextDate(fromDate);
                        transitEdges.add(new TransitEdge(
                                dao.getVoyageNumber(lastLegFrom, destinationUnLocode),
                                lastLegFrom, destinationUnLocode, fromDate, toDate));

                        responseEvent.fire(GraphTraversalResponse.newWithValue(new TransitPath(transitEdges), request));
                    }

                    responseEvent.fire(GraphTraversalResponse.newCompleted(request));
                    
                    logger.info("Path Finder Service called for " + originUnLocode + " to " + destinationUnLocode);
                })
                .exceptionally( e -> {
                    logger.log(Level.SEVERE, e.getMessage(), e);
                    responseEvent.fire(GraphTraversalResponse.newCompletedWithException(e, request));
                    return null;
                });
    }

    private Date nextDate(Date date) {
        return new Date(date.getTime() + ONE_DAY_MS
                + (random.nextInt(1000) - 500) * ONE_MIN_MS);
    }

    private int getRandomNumberOfCandidates() {
        return 3 + random.nextInt(3);
    }

    private List<String> getRandomChunkOfLocations(List<String> allLocations) {
        Collections.shuffle(allLocations);
        int total = allLocations.size();
        int chunk = total > 4 ? 1 + new Random().nextInt(5) : total;
        return allLocations.subList(0, chunk);
    }
}
