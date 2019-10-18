package burp;

import com.google.common.base.Strings;
import com.google.common.hash.Hashing;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.bind.DatatypeConverter;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Utility {

    public static byte[] signRequest(IHttpRequestResponse messageInfo,
                                     IExtensionHelpers helpers,
                                     String service,
                                     String region,
                                     String accessKey,
                                     String secretKey,
                                     String token,
                                     PrintWriter pw) throws Exception {
        IRequestInfo requestInfo = helpers.analyzeRequest(messageInfo);

        List<String> headers = requestInfo.getHeaders();
        if (!token.isEmpty()) {
            boolean tokenExists = false;
            int i = 0;
            for (String header : headers) {
                if (header.toLowerCase().startsWith("x-amz-security-token")) {
                    headers.set(i, "X-Amz-Security-Token: " + token);
                    tokenExists = true;
                }
                i++;
            }
            if (!tokenExists)
                headers.add("X-Amz-Security-Token: " + token);
        }
        List<String> newHeaders = new ArrayList<>(headers);
        headers.remove(0);

        Map<String, String> headerMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        String authHeader = "";
        String amzDate = "";

        for (String header : headers) {
            if (header.toLowerCase().startsWith("authorization:")){
                authHeader = header;
            }
            if (header.toLowerCase().startsWith("x-amz-date:")){
                amzDate = header;
            }

            String[] headerPair = header.split(":",2);
            headerMap.put(headerPair[0].trim(),headerPair[1].trim());
        }

        headers.remove(authHeader);
        headers.remove(amzDate);
        newHeaders.remove(authHeader);
        newHeaders.remove(amzDate);

        SimpleDateFormat amz = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");
        amz.setTimeZone(TimeZone.getTimeZone("UTC"));
        String amzdate = amz.format(new Date());

        SimpleDateFormat dateStamp = new SimpleDateFormat("yyyyMMdd");
        dateStamp.setTimeZone(TimeZone.getTimeZone("UTC"));
        String dateStampString = dateStamp.format(new Date());

        headerMap.put("x-amz-date",amzdate);

        String signedHeaders = getSignedHeaders(headerMap.get("authorization"));
        if (!token.isEmpty() && !signedHeaders.contains("x-amz-security-token")) {
            signedHeaders = signedHeaders + ";x-amz-security-token";
        }

        String[] signedHeaderArray = signedHeaders.split(";");

        List<String> signedHeaderList = Arrays.asList(signedHeaderArray);

        Collections.sort(signedHeaderList);

        StringBuilder canonicalHeaders = new StringBuilder();

        for (String signedHeader : signedHeaderList) {
            if (headerMap.containsKey(signedHeader)) {
                canonicalHeaders.append(signedHeader.toLowerCase()).append(':').append(headerMap.get(signedHeader)).append('\n');
            } else {
                pw.println("Warning: SignedHeader '" + signedHeader + "' does not exist in request headers.");
            }
        }

        byte[] request = messageInfo.getRequest();
        String body = "";
        String payloadHash;

        if (!requestInfo.getMethod().equals("GET")){

            int bodyOffset = requestInfo.getBodyOffset();
            body = new String(request, bodyOffset, request.length - bodyOffset, "UTF-8").trim();
            payloadHash = Hashing.sha256().hashString(body, StandardCharsets.UTF_8).toString().toLowerCase();

        } else {
            payloadHash = Hashing.sha256().hashString("", StandardCharsets.UTF_8).toString().toLowerCase();
        }

        String canonicalUri = requestInfo.getUrl().getPath();
        URI uri = new URI(canonicalUri);
        uri = uri.normalize();
        String path = uri.getPath();
        if(canonicalUri.contains("%")) {
            path = uri.getRawPath();
        }
        String[] segments = path.split("/");
        String[] encodedSegments = new String[segments.length];
        for (int i=0; i<segments.length; i++) {
            encodedSegments[i] = URLEncoder.encode(segments[i], StandardCharsets.UTF_8.toString())
                    .replace("+", "%20").replace("*", "%2A")
                    .replace("%7E", "~");
        }

        String encodedCanonicalUri = String.join("/", encodedSegments);

        // Replace characters we might have lost in the split
        if (path.charAt(path.length()-1) == '/') {
            encodedCanonicalUri = encodedCanonicalUri + "/";
        }

        String canonicalQueryString = requestInfo.getUrl().getQuery();
        if (Strings.isNullOrEmpty(canonicalQueryString)){
            canonicalQueryString = "";
        }

        String[] sorted = canonicalQueryString.split("&");
        Arrays.sort(sorted);

        for (int i = 0; i < sorted.length; ++i) {
            String[] param = sorted[i].split("=");
            for (int j = 0; j < param.length; ++j) {
                try {
                    param[j] = URLEncoder.encode(param[j], StandardCharsets.UTF_8.toString())
                            // OAuth encodes some characters differently:
                            .replace("+", "%20").replace("*", "%2A")
                            .replace("%7E", "~").replace("%25", "%");
                    // This could be done faster with more hand-crafted code.
                } catch (Exception e) {
                    throw new RuntimeException(e.getMessage(), e);
                }
            }
            if (param.length > 1) {
                sorted[i] = String.join("=", param);
            } else if (param.length == 1 && !param[0].isEmpty()){
                sorted[i] = param[0] + "=";
            }
        }
        canonicalQueryString = String.join("&", sorted);

        String[] cleanup = canonicalQueryString.split("");
        for (int i = 0; i < cleanup.length; ++i) {
            if (cleanup[i].equals("%")) {
                cleanup[i+1] = cleanup[i+1].toUpperCase();
                cleanup[i+2] = cleanup[i+2].toUpperCase();
            }
        }
        canonicalQueryString = String.join("", cleanup);

        String canonicalRequest  = requestInfo.getMethod() + '\n' + encodedCanonicalUri + '\n' + canonicalQueryString + '\n' +
                canonicalHeaders +'\n' + signedHeaders + '\n' + payloadHash;
        String credScope = dateStampString + '/' + region + '/' + service + '/' + "aws4_request";
        String algorithm = "AWS4-HMAC-SHA256";

        String stringToSign = algorithm + '\n' + amzdate + '\n' + credScope + '\n' + Hashing.sha256().hashString(canonicalRequest, StandardCharsets.UTF_8).toString().toLowerCase();
        byte[] signingKey = getSignatureKey(secretKey, dateStampString, region, service);

        String signature = DatatypeConverter.printHexBinary(HmacSHA256(stringToSign, signingKey));

        newHeaders.add("Authorization: " + algorithm + ' ' + "Credential=" + accessKey + '/' + credScope + ", " + "SignedHeaders=" +
                signedHeaders + ", " + "Signature=" + signature.toLowerCase());
        newHeaders.add("X-Amz-Date: " + amzdate);

        return helpers.buildHttpMessage(newHeaders, body.getBytes());
    }

    private static byte[] HmacSHA256(String data, byte[] key) throws Exception {
        String algorithm="HmacSHA256";
        Mac mac = Mac.getInstance(algorithm);
        mac.init(new SecretKeySpec(key, algorithm));
        return mac.doFinal(data.getBytes("UTF8"));
    }

    private static byte[] getSignatureKey(String key, String dateStamp, String regionName, String serviceName) throws Exception {
        byte[] kSecret = ("AWS4" + key).getBytes("UTF8");
        byte[] kDate = HmacSHA256(dateStamp, kSecret);
        byte[] kRegion = HmacSHA256(regionName, kDate);
        byte[] kService = HmacSHA256(serviceName, kRegion);
        return HmacSHA256("aws4_request", kService);
    }

    private static String getSignedHeaders(String authHeader){

        String signedHeaders = "";

        Pattern pattern = Pattern.compile("SignedHeaders=(.*?)[,\\s]");

        Matcher matcher = pattern.matcher(authHeader);
        if (matcher.find()){
            signedHeaders = matcher.group(1);
        }

        return  signedHeaders;

    }
}
