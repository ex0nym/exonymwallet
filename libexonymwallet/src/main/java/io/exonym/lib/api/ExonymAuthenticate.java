package io.exonym.lib.api;

import com.google.gson.JsonObject;
import eu.abc4trust.xml.*;
import io.exonym.lib.abc.util.JaxbHelper;
import io.exonym.lib.exceptions.*;
import io.exonym.lib.helpers.Parser;
import io.exonym.lib.helpers.Timing;
import io.exonym.lib.helpers.UIDHelper;
import io.exonym.lib.lite.ModelCommandProcessor;
import io.exonym.lib.lite.Msg;
import io.exonym.lib.pojo.*;
import io.exonym.lib.wallet.ExonymOwner;
import org.apache.commons.codec.binary.Base64;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class ExonymAuthenticate extends ModelCommandProcessor {

    private final static Logger logger = Logger.getLogger(ExonymAuthenticate.class.getName());
    
    private ConcurrentHashMap<String, ExonymChallenge> challengeToAuthenticationRequest = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, URI> challengeToDomainContext = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, String> challengeToSessionId = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, Long> challengeToT0 = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, String> sessionIdToChallenge = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, EndonymToken> sessionIdToEndonym = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, EndonymToken> sessionIdToErrors = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, ConcurrentHashMap<URI, EndonymToken>> authSessionIdToEndonym = new ConcurrentHashMap<>();

    protected void challenge(SsoChallenge challenge, String sessionId){
        String c = challenge.getChallenge();
        logger.info("Setup challenge " + c);
        challengeToAuthenticationRequest.put(c, challenge);
        sessionIdToChallenge.put(sessionId, c);
        challengeToDomainContext.put(challenge.getChallenge(), challenge.getDomain());
        logger.info("Putting challenge and domain=" + challengeToDomainContext);

    }

    protected void removeSession(String session){
        this.authSessionIdToEndonym.remove(session);

    }

    protected URI probeForContext(String sessionId) throws UxException {
        String challenge = sessionIdToChallenge.remove(sessionId);
        if (challenge!=null){
            challengeToSessionId.put(challenge, sessionId);
            challengeToT0.put(challenge, Timing.currentTime());
            URI domain = challengeToDomainContext.get(challenge);
            logger.info("(getting) challengeToDomainContext=" + challengeToDomainContext);
            return domain;

        } else {
            throw new UxException(ErrorMessages.TIME_OUT);
        }
    }

    protected void authenticate(String token) throws UxException, HubException {
        long t0 = Timing.currentTime();
        String sessionId = null;
        try {
            if (token != null) {
                PresentationToken pt = Parser.parsePresentationTokenFromXml(token);
                String message = extractMessage(pt);
                String challenge = extractValueFromJson(message, "c");
                sessionId = challengeToSessionId.get(challenge);

                logger.info("Retrieved Challenge for session=" + sessionId + " challenge=" + challenge);
                authenticateToken(pt, challenge);
                logger.info("Authenticated token successfully");

                synchronized (sessionId) {
                    logger.info("Adding authorised session " + sessionId);
                    addAuthorizedSession(sessionId, challenge);
                    sessionId.notifyAll();
                    logger.info("Authentication Duration = " + Timing.hasBeenMs(t0));

                }
            } else {
                throw new NullPointerException("No Token Provided");

            }
        } catch (PolicyNotSatisfiedException e) {
            if (sessionId!=null){
                completeRequest(sessionId, e);

            }
            throw new UxException(ErrorMessages.FAILED_TO_AUTHORIZE, e);

        } catch (Exception e) {
            if (sessionId==null){
                throw new HubException(ErrorMessages.SERVER_SIDE_PROGRAMMING_ERROR, e);

            } else {
                completeRequest(sessionId, e);

            }
        }
    }

    private String extractMessage(PresentationToken pt) {
        return new String(
                pt.getPresentationTokenDescription().getMessage().getNonce(),
                StandardCharsets.UTF_8);
    }

    private String extractValueFromJson(String kv, String keyOfKv){
        JsonObject o = JaxbHelper.gson.fromJson(kv, JsonObject.class);
        return o.get(keyOfKv).getAsString();

    }


    private void completeRequest(String sessionId, Exception e) {
        synchronized (sessionId){
            EndonymToken nym = sessionIdToEndonym.remove(sessionId);
            nym.setError(e.getMessage());
            sessionIdToErrors.put(sessionId, nym);
            sessionId.notifyAll();

        }
    }

    protected EndonymToken isAuthenticatedWait(String sessionId, URI context, long timeout) throws UxException {
        synchronized (sessionId){
            try {
                boolean alreadyAuth = isAuthenticatedQuiet(sessionId, context);
                if (alreadyAuth){
                    return isAuthenticated(sessionId, context);

                } else {
                    long t0 = Timing.currentTime();
                    logger.info("Waiting for authentication " + sessionId + " timeout=" + timeout);
                    sessionId.wait(timeout);

                    if (Timing.hasBeen(t0, timeout)){
                        EndonymToken result = new EndonymToken();
                        result.setTimeout(true);
                        return result;

                    } else {
                        logger.info("Trying auth after notification " + sessionId);
                        return this.isAuthenticated(sessionId, context);

                    }
                }
            } catch (InterruptedException e) {
                return this.isAuthenticated(sessionId, context);

            }
        }
    }

    /**
     * Check for authorized sessions.
     *
     * @param sessionId
     * @return The endonyms associated with the session.
     * @throws UxException If the session is not authorized.
     *
     */
    protected EndonymToken isAuthenticated(String sessionId, URI context) throws UxException {
        ConcurrentHashMap<URI, EndonymToken> contextToEndonym = authSessionIdToEndonym.get(sessionId);
        if (contextToEndonym!=null) {
            logger.info("contextToEndonym " + contextToEndonym + " context=" + context);
            EndonymToken endonym = contextToEndonym.get(context);
            if (endonym != null) {
                return endonym;

            } else {
                EndonymToken token = sessionIdToErrors.remove(sessionId);
                if (token!=null){
                    return token;

                } else {
                    throw new UxException(ErrorMessages.FAILED_TO_AUTHORIZE);

                }
            }
        } else {
            logger.info("No contextToEndonym Map " + contextToEndonym);
            throw new UxException(ErrorMessages.FAILED_TO_AUTHORIZE);

        }
    }

    protected boolean isAuthenticatedQuiet(String sessionId, URI context)  {
        try{
            isAuthenticated(sessionId, context);
            return true;

        } catch (Exception e){
            logger.throwing("ExonymAuthenticate.class",
                    "isAuthenticatedQuiet()", e);
            return false;
        }
    }


    private void addAuthorizedSession(String sessionId, String challenge) {
        logger.info("(z) challengeToDomainContext=" + challengeToDomainContext);
        ConcurrentHashMap<URI, EndonymToken> contexts = authSessionIdToEndonym.get(sessionId);
        URI context = challengeToDomainContext.get(challenge);

        if (contexts==null){
            contexts = new ConcurrentHashMap<>();
            authSessionIdToEndonym.put(sessionId, contexts);
            logger.info("created map for session " + contexts);


        }
        EndonymToken nym = sessionIdToEndonym.remove(sessionId);
        contexts.put(context, nym);

        logger.info("context= " + context + " nym=" + nym + " onto=" + contexts);
        logger.info("authSessionIdToEndonym= " + authSessionIdToEndonym);

    }


    protected SsoChallenge authIfNeeded(SsoConfiguration config, String sessionId) throws AlreadyAuthException {
        if (!isAuthenticatedQuiet(sessionId, config.getDomain())){
            return SsoChallenge.newChallenge(config);

        } else {
            throw new AlreadyAuthException();

        }
    }



    private String authenticateToken(PresentationToken pt, String challenge) throws Exception {

        String sessionId = challengeToSessionId.get(challenge);
        ExonymChallenge c = challengeToAuthenticationRequest.remove(challenge);

        logger.info("challenge/sessionId at authenticate (no value should be null)="
                + challenge + " " + sessionId + " " + c);

        PresentationPolicyAlternatives ppa = verifyOfferingAndBuildPolicy(c, sessionId, pt);
        logger.info("Pps computed");
        ExonymOwner owner = ExonymOwner.verifierOnly();
        logger.info("Verifier Only");
        owner.verifyClaim(ppa, pt);
        logger.info("Verified Claim");
        return challenge;

    }

    private PresentationPolicyAlternatives verifyOfferingAndBuildPolicy(ExonymChallenge c,
                                     String sessionId, PresentationToken pt) throws Exception {
        if (c instanceof SsoChallenge){
            return juxtaposeTokenAndSsoChallenge((SsoChallenge)c, sessionId, pt);

        } else if (c instanceof DelegateRequest) {
            return juxtaposeTokenAndDelegateChallenge((DelegateRequest) c, sessionId, pt);

        } else if (c==null){
            throw new UxException(ErrorMessages.UNEXPECTED_TOKEN_FOR_THIS_NODE_OR_AUTH_TIMEOUT);

        } else {
            throw new HubException("Unsupported Challenge Type " + c);

        }
    }

    private PresentationPolicyAlternatives juxtaposeTokenAndSsoChallenge(SsoChallenge c,
                          String sessionId, PresentationToken pt) throws Exception {
        PresentationPolicy policy = checkPseudonym(sessionId, c, pt);
        PresentationTokenDescription ptd = pt.getPresentationTokenDescription();

        String challengeIn = Base64.encodeBase64String(ptd.getMessage().getNonce());
        if (!c.getChallenge().equals(challengeIn)){
            throw new UxException(ErrorMessages.TOKEN_INVALID + ":replay");

        }
        if (c.isSybil() || !c.getHonestUnder().isEmpty()){
            HashMap<String, CredentialInToken> rulebookIdToCredentialMap = checkSybil(policy, ptd);

            if (!c.getHonestUnder().isEmpty()){
                checkRulebooks(sessionId, c, rulebookIdToCredentialMap);

            }
        }
        PresentationPolicyAlternatives ppa = new PresentationPolicyAlternatives();
        ppa.getPresentationPolicy().add(policy);
        return ppa;
    }

    private void checkRulebooks(String sessionId, SsoChallenge c,
                                HashMap<String, CredentialInToken> rcMap) throws Exception {

        HashMap<String, RulebookAuth> requests = c.getHonestUnder();

        for (String rulebook : requests.keySet()){
            RulebookAuth auth = requests.get(rulebook);
            CredentialInToken cit = rcMap.get(rulebook);
            URI modUid = UIDHelper.computeModUidFromMaterialUID(cit.getIssuerParametersUID());
            logger.info("Got ModID at check rulebooks= " + modUid);

            if (auth.getModBlacklist()
                    .contains(modUid)){
                throw new UxException(ErrorMessages.BLACKLISTED_MODERATOR);
            }
            URI leadUID = UIDHelper.computeLeadUidFromModUid(modUid);
            logger.info("Got LeadID at check rulebooks= " + leadUID);

            if (auth.getLeadBlacklist()
                    .contains(leadUID)){
                throw new UxException(ErrorMessages.BLACKLISTED_LEAD);

            }
            EndonymToken et = sessionIdToEndonym.get(sessionId);
            if (et!=null){
                et.setModeratorUid(modUid);
            } else {
                logger.warning("THERE WAS NO TOKEN ASSOCIATED WITH THIS SESSION");
            }
        }
    }

    private PresentationPolicy checkPseudonym(String sessionId, ExonymChallenge c,
                                              PresentationToken pt) throws HubException, UxException {
        String domain = c.getDomain().toString();
        PresentationTokenDescription ptd = pt.getPresentationTokenDescription();

        List<PseudonymInToken> nyms = ptd.getPseudonym();
        PresentationPolicy pp = new PresentationPolicy();
        pp.setMessage(ptd.getMessage());
        pp.setPolicyUID(ptd.getPolicyUID());
        boolean hasBasis = false;
        boolean hasExclusive = false;

        for (PseudonymInToken nym : nyms){
            if (nym.getScope().equals(domain)){
                if (nym.isExclusive() && !hasExclusive){
                    pp.getPseudonym().add(Parser.nymInTokenToPolicy(nym));

                    URI endonym = EndonymToken.endonymForm(nym.getScope(), nym.getPseudonymValue());

                    logger.info("sessionIdToEndonym=" + sessionIdToEndonym);
                    EndonymToken et = EndonymToken.build(endonym, pt);
                    this.sessionIdToEndonym.put(sessionId, et);
                    hasExclusive = true;

                }
            } else {
                if (!nym.isExclusive()){
                    pp.getPseudonym().add(Parser.nymInTokenToPolicy(nym));
                    hasBasis = true;

                }
            }
        }
        if (hasExclusive && hasBasis){
            return pp;

        } else {
            throw new HubException(ErrorMessages.UNEXPECTED_PSEUDONYM_REQUEST,
                    "The correct pseudonym was not provided",
                    "hasBasis=" + hasBasis,
                    "hasExclusive=" + hasExclusive);

        }
    }

    private HashMap<String, CredentialInToken> checkSybil(PresentationPolicy buildingPolicy,
                                                          PresentationTokenDescription ptd) throws Exception {
        String sybilUID = Rulebook.SYBIL_RULEBOOK_HASH_TEST;
        logger.info("SybilUID:" + sybilUID);

        boolean foundSybil = false;
        HashMap<String, CredentialInToken> map = new HashMap<>();
        List<CredentialInPolicy> credentials = new ArrayList<>();
        for (CredentialInToken cit : ptd.getCredential()){

            logger.info("Issuer UID:" + cit.getIssuerParametersUID());

            URI rid = UIDHelper.computeRulebookUidFromNodeUid(
                    cit.getIssuerParametersUID());

            logger.info("Rulebook ID:" + rid);

            map.put(rid.toString(), cit);
            credentials.add(Parser.credentialInTokenToPolicy(cit));
            if (cit.getIssuerParametersUID().toString().contains(sybilUID)){
                foundSybil = true;

            }
        }
        buildingPolicy.getCredential().addAll(credentials);

        if (foundSybil){
            return map;

        } else {
            throw new HubException(ErrorMessages.SYBIL_WARN,
                    "A sybil credential was requested and none was provided");

        }

    }

    private PresentationPolicyAlternatives juxtaposeTokenAndDelegateChallenge(
            DelegateRequest c, String cb64, PresentationToken pt) {
        return null;
    }


    protected ExonymAuthenticate(){
        super(1,"ExonymAuthenticate", 120000);

    }

    protected long challengeTimeout(){
        return 60000;

    }

    @Override
    protected void periodOfInactivityProcesses() {
        ArrayList<String> cleanup = new ArrayList<>();

        for (String challenge : challengeToT0.keySet()){
            long t0 = challengeToT0.get(challenge);
            if (Timing.hasBeen(t0, this.challengeTimeout())){
                cleanup.add(challenge);

            }
        }
        for (String c : cleanup){
            logger.info("Cleaning up " + c);
            challengeToT0.remove(c);
            String sessionId = challengeToSessionId.remove(c);
            challengeToAuthenticationRequest.remove(c);
            challengeToDomainContext.remove(c);
//            sessionIdToChallenge.remove(sessionId);
//            sessionIdToEndonym.remove(sessionId);

        }
    }

    @Override
    protected void receivedMessage(Msg msg) {
        // do nothing
    }
}
