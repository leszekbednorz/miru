package com.jivesoftware.os.miru.reco.plugins.trending;

import com.jivesoftware.os.jive.utils.jaxrs.util.ResponseHelper;
import com.jivesoftware.os.jive.utils.logger.MetricLogger;
import com.jivesoftware.os.jive.utils.logger.MetricLoggerFactory;
import com.jivesoftware.os.miru.api.activity.MiruPartitionId;
import com.jivesoftware.os.miru.query.MiruPartitionUnavailableException;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import static com.jivesoftware.os.miru.reco.plugins.trending.TrendingConstants.CUSTOM_QUERY_ENDPOINT;
import static com.jivesoftware.os.miru.reco.plugins.trending.TrendingConstants.TRENDING_PREFIX;

@Path(TRENDING_PREFIX)
public class TrendingEndpoints {

    private static final MetricLogger log = MetricLoggerFactory.getLogger();

    private final TrendingInjectable injectable;
    private final ResponseHelper responseHelper = ResponseHelper.INSTANCE;

    public TrendingEndpoints(@Context TrendingInjectable injectable) {
        this.injectable = injectable;
    }

    @POST
    @Path(CUSTOM_QUERY_ENDPOINT)
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response scoreTrending(TrendingQuery query) {
        try {
            long t = System.currentTimeMillis();
            TrendingResult result = injectable.scoreTrending(query);

            log.info("scoreTrending: " + result.results.size() + " / " + result.collectedDistincts + " in " + (System.currentTimeMillis() - t) + " ms");
            return responseHelper.jsonResponse(result != null ? result : TrendingResult.EMPTY_RESULTS);
        } catch (MiruPartitionUnavailableException e) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity("Partition unavailable").build();
        } catch (Exception e) {
            log.error("Failed to score trending.", e);
            return Response.serverError().build();
        }
    }

    @POST
    @Path(CUSTOM_QUERY_ENDPOINT + "/{partitionId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response scoreTrending(@PathParam("partitionId") int id, TrendingQueryAndResult queryAndResult) {
        MiruPartitionId partitionId = MiruPartitionId.of(id);
        try {
            TrendingResult result = injectable.scoreTrending(partitionId, queryAndResult);

            //log.info("scoreTrending: " + result.collectedDistincts);
            return responseHelper.jsonResponse(result != null ? result : TrendingResult.EMPTY_RESULTS);
        } catch (MiruPartitionUnavailableException e) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity("Partition unavailable").build();
        } catch (Exception e) {
            log.error("Failed to score trending for partition: " + partitionId.getId(), e);
            return Response.serverError().build();
        }
    }
}
