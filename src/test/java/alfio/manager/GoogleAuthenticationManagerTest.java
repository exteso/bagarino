package alfio.manager;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class GoogleAuthenticationManagerTest
{
    private final String DOMAIN = "domain_test";
    private final String CLIENT_ID = "123";
    private final String CLIENT_SECRET = "1234";
    private final String CALLBACK_URI = "callback";
    private final String CLAIMS_URI = "/claims";

    private final GoogleAuthenticationManager authenticationManager = new GoogleAuthenticationManager(DOMAIN, CLIENT_ID, CLIENT_SECRET, CALLBACK_URI, CLAIMS_URI);

    @Test
    public void google_authorize_url_test()
    {
        List<String> scopes = Arrays.asList("scope1", "scope2");
        String redirectURL = authenticationManager.buildAuthorizeUrl(scopes);
        String expectedURL = "https://domain_test/o/oauth2/v2/auth?redirect_uri=callback&client_id=123&scope=scope1%20scope2&response_type=code";
        Assert.assertEquals(expectedURL, redirectURL);
    }

    @Test
    public void google_claims_url_test()
    {
        String claimsUrl = authenticationManager.buildClaimsRetrieverUrl();
        String expectedURL = "https://domain_test/claims";
        Assert.assertEquals(expectedURL, claimsUrl);
    }

    @Test
    public void google_build_body_test() throws JsonProcessingException
    {
        String code = "code";
        String body = authenticationManager.buildRetrieveClaimsUrlBody(code);
        String expectedBody = "{\"code\":\"code\",\"grant_type\":\"authorization_code\",\"client_secret\":\"1234\",\"redirect_uri\":\"callback\",\"client_id\":\"123\"}";
        Assert.assertEquals(expectedBody, body);
    }

    @Test
    public void google_parameters_name_test(){
        Assert.assertEquals("code", authenticationManager.getCodeNameParameter());
        Assert.assertEquals("access_token", authenticationManager.getAccessTokenNameParameter());
        Assert.assertEquals("id_token", authenticationManager.getIdTokenNameParameter());
        Assert.assertEquals("sub", authenticationManager.getSubjectNameParameter());
    }
}