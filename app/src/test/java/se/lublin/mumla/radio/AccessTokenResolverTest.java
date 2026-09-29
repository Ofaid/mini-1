package se.lublin.mumla.radio;

import static org.junit.Assert.assertEquals;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class AccessTokenResolverTest {
    @Test
    public void resolvesOnlyTokensForTheSelectedChannel() throws JSONException {
        JSONObject first = channel("{\"mode\":\"public\","
                + "\"tokens\":[\" Alpha-token \",\"SHARED\",\"Alpha-token\"]}");
        JSONObject second = channel("{\"mode\":\"public\",\"token\":\"Beta-token\"}");

        assertEquals(Arrays.asList("Alpha-token", "SHARED"),
                AccessTokenResolver.resolve(first));
        assertEquals(Collections.singletonList("Beta-token"),
                AccessTokenResolver.resolve(second));
    }

    @Test
    public void excludesProtectedNoneAndMalformedAccess() throws JSONException {
        assertEquals(Collections.emptyList(), AccessTokenResolver.resolve(
                channel("{\"mode\":\"protected\",\"token\":\"secret\"}")));
        assertEquals(Collections.emptyList(), AccessTokenResolver.resolve(
                channel("{\"mode\":\"none\",\"token\":\"unused\"}")));
        assertEquals(Collections.emptyList(), AccessTokenResolver.resolve(new JSONObject("{}")));
        assertEquals(Collections.emptyList(), AccessTokenResolver.resolve(null));
    }

    private static JSONObject channel(String access) throws JSONException {
        return new JSONObject("{\"access\":" + access + "}");
    }
}
