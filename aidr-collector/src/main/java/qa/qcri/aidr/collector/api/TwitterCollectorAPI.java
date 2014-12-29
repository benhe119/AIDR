/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package qa.qcri.aidr.collector.api;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.glassfish.jersey.jackson.JacksonFeature;
import qa.qcri.aidr.collector.beans.CollectionTask;
import qa.qcri.aidr.collector.beans.ResponseWrapper;
import qa.qcri.aidr.collector.collectors.TwitterStreamTracker;

import qa.qcri.aidr.collector.utils.GenericCache;
import qa.qcri.aidr.collector.utils.TwitterStreamQueryBuilder;
import qa.qcri.aidr.common.logging.ErrorLog;
import twitter4j.conf.ConfigurationBuilder;

import javax.ws.rs.*;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.List;

import static qa.qcri.aidr.collector.utils.ConfigProperties.getProperty;

/**
 * REST Web Service
 *
 * @author Imran
 */
@Path("/twitter")
public class TwitterCollectorAPI {

    private static Logger logger = Logger.getLogger(TwitterCollectorAPI.class.getName());
    private static ErrorLog elog = new ErrorLog();

    @Context
    private UriInfo context;

    public TwitterCollectorAPI() {
    }

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/start")
    public Response startTask(CollectionTask collectionTask) {
        logger.info("Collection start request received for " + collectionTask.getCollectionCode());
        logger.info("Details:\n" + collectionTask.toString());
        ResponseWrapper response = new ResponseWrapper();

        //check if all twitter specific information is available in the request
        if (!collectionTask.isTwitterInfoPresent()) {
            response.setStatusCode(getProperty("STATUS_CODE_COLLECTION_ERROR"));
            response.setMessage("One or more Twitter authentication token(s) are missing");
            return Response.ok(response).build();
        }

        //check if all query parameters are missing in the query
        if (!collectionTask.isToTrackAvailable() && !collectionTask.isToFollowAvailable() && !collectionTask.isGeoLocationAvailable()) {
            response.setStatusCode(getProperty("STATUS_CODE_COLLECTION_ERROR"));
            response.setMessage("Missing one or more fields (toTrack, toFollow, and geoLocation). At least one field is required");
            return Response.ok(response).build();
        }

        ConfigurationBuilder configurationBuilder = new ConfigurationBuilder();
        configurationBuilder.setDebugEnabled(false)
                .setJSONStoreEnabled(true)
                .setOAuthConsumerKey(collectionTask.getConsumerKey())
                .setOAuthConsumerSecret(collectionTask.getConsumerSecret())
                .setOAuthAccessToken(collectionTask.getAccessToken())
                .setOAuthAccessTokenSecret(collectionTask.getAccessTokenSecret());

        //check if a task is already running with same configutations
        logger.info("Checking OAuth parameters for " + collectionTask.getCollectionCode());
        if (GenericCache.getInstance().isTwtConfigExists(collectionTask)) {
            String msg = "Provided OAuth configurations already in use. Please stop this collection and then start again.";
            logger.info(collectionTask.getCollectionCode() + ": " + msg);
            response.setMessage(msg);
            response.setStatusCode(getProperty("STATUS_CODE_COLLECTION_ERROR"));
            return Response.ok(response).build();
        }

        String collectionCode = collectionTask.getCollectionCode();

        //building filter for filtering twitter stream
        logger.info("Building query for Twitter streaming API for collection " + collectionCode);
        TwitterStreamQueryBuilder queryBuilder = null;
        try {
            String langFilter = StringUtils.isNotEmpty(collectionTask.getLanguageFilter()) ? collectionTask.getLanguageFilter() : getProperty("LANGUAGE_ALLOWED_ALL");
            queryBuilder = new TwitterStreamQueryBuilder(collectionTask.getToTrack(), collectionTask.getToFollow(), collectionTask.getGeoLocation(), langFilter);
        } catch (Exception e) {
            response.setMessage(e.getMessage());
            response.setStatusCode(getProperty("STATUS_CODE_COLLECTION_ERROR"));
            return Response.ok(response).build();
        }

        collectionTask.setStatusCode(getProperty("STATUS_CODE_COLLECTION_INITIALIZING"));
        logger.info("Initializing connection with Twitter streaming API for collection " + collectionCode);
        TwitterStreamTracker tracker;
        try {
            tracker = new TwitterStreamTracker(queryBuilder, configurationBuilder, collectionTask);
        } catch (Exception ex) {
            logger.error("Exception in creating TwitterStreamTracker for collection " + collectionCode);
            logger.error(elog.toStringException(ex));
        }

        if (Boolean.valueOf(getProperty("DEFAULT_PERSISTANCE_MODE"))) {
            startPersister(collectionCode);
        }

        response.setMessage(getProperty("STATUS_CODE_COLLECTION_INITIALIZING"));
        response.setStatusCode(getProperty("STATUS_CODE_COLLECTION_INITIALIZING"));
        return Response.ok(response).build();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/stop")
    public Response stopTask(@QueryParam("id") String collectionCode) throws InterruptedException {
        GenericCache cache = GenericCache.getInstance();
        TwitterStreamTracker tracker = cache.getTwitterTracker(collectionCode);
        CollectionTask task = cache.getConfig(collectionCode);

        cache.delFailedCollection(collectionCode);
        cache.deleteCounter(collectionCode);
        cache.delTwtConfigMap(collectionCode);
        cache.delLastDownloadedDoc(collectionCode);
        cache.delTwitterTracker(collectionCode);

        if (tracker != null) {
            tracker.abortCollection();

            if (Boolean.valueOf(getProperty("DEFAULT_PERSISTANCE_MODE"))) {
                stopPersister(collectionCode);
            }

            logger.info(collectionCode + ": " + "Collector has been successfully stopped.");
        } else {
            logger.info("No collector instances found to be stopped with the given id:" + collectionCode);
        }

        if (task != null) {
            return Response.ok(task).build();
        }

        ResponseWrapper response = new ResponseWrapper();
        response.setMessage("Invalid key. No running collector found for the given id.");
        response.setStatusCode(getProperty("STATUS_CODE_COLLECTION_NOTFOUND"));
        return Response.ok(response).build();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/status")
    public Response getStatus(@QueryParam("id") String id) {
        ResponseWrapper response = new ResponseWrapper();
        String responseMsg = null;
        if (StringUtils.isEmpty(id)) {
            response.setMessage("Invalid key. No running collector found for the given id.");
            response.setStatusCode(getProperty("STATUS_CODE_COLLECTION_NOTFOUND"));
            return Response.ok(response).build();
        }
        CollectionTask task = GenericCache.getInstance().getConfig(id);
        if (task != null) {
            return Response.ok(task).build();
        }

        CollectionTask failedTask = GenericCache.getInstance().getFailedCollectionTask(id);
        if (failedTask != null) {
            return Response.ok(failedTask).build();
        }

        response.setMessage("Invalid key. No running collector found for the given id.");
        response.setStatusCode(getProperty("STATUS_CODE_COLLECTION_NOTFOUND"));
        return Response.ok(response).build();

    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/restart")
    public Response restartCollection(@QueryParam("code") String collectionCode) throws InterruptedException {
        List<CollectionTask> collections = GenericCache.getInstance().getAllRunningCollectionTasks();
        CollectionTask collectionToRestart = null;
        for (CollectionTask collection : collections) {
            if (collection.getCollectionCode().equalsIgnoreCase(collectionCode)) {
                collectionToRestart = collection;
                break;
            }
        }
        stopTask(collectionCode);
        Thread.sleep(3000);
        Response response = startTask(collectionToRestart);
        return response;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/status/all")
    public Response getStatusAll() {
        List<CollectionTask> allTasks = GenericCache.getInstance().getAllConfigs();
        return Response.ok(allTasks).build();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/failed/all")
    public Response getAllFailedCollections() {
        List<CollectionTask> allTasks = GenericCache.getInstance().getAllFailedCollections();
        return Response.ok(allTasks).build();
    }

    @Deprecated
    public void startCollectorPersister(String collectionCode) {
        Client client = ClientBuilder.newBuilder().register(JacksonFeature.class).build();
        try {
            WebTarget webResource = client.target(getProperty("PERSISTER_REST_URI") + "persister/start?file="
                    + URLEncoder.encode(getProperty("DEFAULT_PERSISTER_FILE_LOCATION"), "UTF-8")
                    + "&collectionCode=" + URLEncoder.encode(collectionCode, "UTF-8"));
            Response clientResponse = webResource.request(MediaType.APPLICATION_JSON).get();
            String jsonResponse = clientResponse.readEntity(String.class);

            logger.info(collectionCode + ": Collector persister response = " + jsonResponse);
        } catch (RuntimeException e) {
            logger.error(collectionCode + ": Could not start persister. Is persister running?");
            logger.error(elog.toStringException(e));
        } catch (UnsupportedEncodingException e) {
            // TODO Auto-generated catch block
            logger.error(collectionCode + ": Unsupported Encoding scheme used");
        }
    }

    public void startPersister(String collectionCode) {
        Client client = ClientBuilder.newBuilder().register(JacksonFeature.class).build();
        try {
            WebTarget webResource = client.target(getProperty("PERSISTER_REST_URI") + "collectionPersister/start?channel_provider="
                    + URLEncoder.encode(getProperty("TAGGER_CHANNEL"), "UTF-8")
                    + "&collection_code=" + URLEncoder.encode(collectionCode, "UTF-8"));
            Response clientResponse = webResource.request(MediaType.APPLICATION_JSON).get();
            String jsonResponse = clientResponse.readEntity(String.class);

            logger.info(collectionCode + ": Collector persister response = " + jsonResponse);
        } catch (RuntimeException e) {
            logger.error(collectionCode + ": Could not start persister. Is persister running?");
            logger.error(elog.toStringException(e));
        } catch (UnsupportedEncodingException e) {
            // TODO Auto-generated catch block
            logger.error(collectionCode + ": Unsupported Encoding scheme used");
        }
    }

    @Deprecated
    public void stopCollectorPersister(String collectionCode) {
        Client client = ClientBuilder.newBuilder().register(JacksonFeature.class).build();
        try {
            WebTarget webResource = client.target(getProperty("PERSISTER_REST_URI")
                    + "persister/stop?collectionCode=" + URLEncoder.encode(collectionCode, "UTF-8"));
            Response clientResponse = webResource.request(MediaType.APPLICATION_JSON).get();
            String jsonResponse = clientResponse.readEntity(String.class);
            logger.info(collectionCode + ": Collector persister response =  " + jsonResponse);
        } catch (RuntimeException e) {
            logger.error(collectionCode + ": Could not stop persister. Is persister running?");
            logger.error(elog.toStringException(e));
        } catch (UnsupportedEncodingException e) {
            // TODO Auto-generated catch block
            logger.error(collectionCode + ": Unsupported Encoding scheme used");
        }
    }

    public void stopPersister(String collectionCode) {
        Client client = ClientBuilder.newBuilder().register(JacksonFeature.class).build();
        try {
            WebTarget webResource = client.target(getProperty("PERSISTER_REST_URI")
                    + "collectionPersister/stop?collection_code=" + URLEncoder.encode(collectionCode, "UTF-8"));
            Response clientResponse = webResource.request(MediaType.APPLICATION_JSON).get();
            String jsonResponse = clientResponse.readEntity(String.class);
            logger.info(collectionCode + ": Collector persister response =  " + jsonResponse);
        } catch (RuntimeException e) {
            logger.error(collectionCode + ": Could not stop persister. Is persister running?");
            logger.error(elog.toStringException(e));
        } catch (UnsupportedEncodingException e) {
            // TODO Auto-generated catch block
            logger.error(collectionCode + ": Unsupported Encoding scheme used");
        }
    }

    @Deprecated
    public void startTaggerPersister(String collectionCode) {
        Client client = ClientBuilder.newBuilder().register(JacksonFeature.class).build();
        try {
            WebTarget webResource = client.target(getProperty("PERSISTER_REST_URI") + "taggerPersister/start?file="
                    + URLEncoder.encode(getProperty("DEFAULT_PERSISTER_FILE_LOCATION"), "UTF-8")
                    + "&collectionCode=" + URLEncoder.encode(collectionCode, "UTF-8"));
            Response clientResponse = webResource.request(MediaType.APPLICATION_JSON).get();
            String jsonResponse = clientResponse.readEntity(String.class);
            logger.info(collectionCode + ": Tagger persister response = " + jsonResponse);
        } catch (RuntimeException e) {
            logger.error(collectionCode + ": Could not start persister. Is persister running?");
            logger.error(elog.toStringException(e));
        } catch (UnsupportedEncodingException e) {
            // TODO Auto-generated catch block
            logger.error(collectionCode + ": Unsupported Encoding scheme used");
        }
    }

    @Deprecated
    public void stopTaggerPersister(String collectionCode) {
        Client client = ClientBuilder.newBuilder().register(JacksonFeature.class).build();
        try {
            WebTarget webResource = client.target(getProperty("PERSISTER_REST_URI")
                    + "taggerPersister/stop?collectionCode=" + URLEncoder.encode(collectionCode, "UTF-8"));
            Response clientResponse = webResource.request(MediaType.APPLICATION_JSON).get();
            String jsonResponse = clientResponse.readEntity(String.class);
            logger.info(collectionCode + ": Tagger persister response: " + jsonResponse);
        } catch (RuntimeException e) {
            logger.error(collectionCode + ": Could not stop persister. Is persister running?");
            logger.error(elog.toStringException(e));
        } catch (UnsupportedEncodingException e) {
            // TODO Auto-generated catch block
            logger.error(collectionCode + ": Unsupported Encoding scheme used");
        }
    }
}
