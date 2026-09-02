package receipt_tax;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
public class ReceiptController {

    @PostMapping("/api/analyze")
    public String analyze(@RequestParam("receipt") MultipartFile receipt) {

        if (receipt.isEmpty()) {
            return "영수증 사진을 선택해주세요.";
        }

        String apiUrl = System.getenv("CLOVA_OCR_URL");
        String secretKey = System.getenv("CLOVA_OCR_SECRET");

        if (apiUrl == null || apiUrl.isBlank()) {
            return "CLOVA_OCR_URL 환경변수가 없습니다.";
        }

        if (secretKey == null || secretKey.isBlank()) {
            return "CLOVA_OCR_SECRET 환경변수가 없습니다.";
        }

        try {

            // ==============================
            // 1. CLOVA OCR 요청
            // ==============================

            String format = getImageFormat(receipt);

            String base64Image =
                    Base64.getEncoder()
                            .encodeToString(receipt.getBytes());

            String requestBody =
                    "{"
                            + "\"version\":\"V2\","
                            + "\"requestId\":\""
                            + UUID.randomUUID()
                            + "\","
                            + "\"timestamp\":"
                            + System.currentTimeMillis()
                            + ","
                            + "\"lang\":\"ko\","
                            + "\"images\":["
                            + "{"
                            + "\"format\":\""
                            + format
                            + "\","
                            + "\"name\":\"receipt\","
                            + "\"data\":\""
                            + base64Image
                            + "\""
                            + "}"
                            + "],"
                            + "\"enableTableDetection\":false"
                            + "}";

            String result =
                    callClovaOcr(
                            apiUrl,
                            secretKey,
                            requestBody
                    );

            // ==============================
            // 2. OCR 글자 추출
            // ==============================

            String ocrText =
                    extractOcrText(result);

            // ==============================
            // 3. 필요한 정보 추출
            // ==============================

            String businessNumber =
                    extractBusinessNumber(ocrText);

            long taxFreeAmount =
                    extractTaxFreeAmount(ocrText);

            long vatAmount =
                    extractVatAmount(ocrText);

            long totalAmount =
                    extractTotalAmount(ocrText);

            // ==============================
            // 4. V5 / ZZ 판별
            // ==============================

            String taxCode =
                    classifyTaxCode(
                            taxFreeAmount,
                            vatAmount
                    );

            // ==============================
            // 5. 결과 출력
            // ==============================

            String output =
                    "===== 영수증 분석 결과 =====\n"
                            + "사업자번호: "
                            + businessNumber
                            + "\n"
                            + "총액: "
                            + formatMoney(totalAmount)
                            + "\n"
                            + "면세금액: "
                            + formatMoney(taxFreeAmount)
                            + "\n"
                            + "부가세: "
                            + formatMoney(vatAmount)
                            + "\n"
                            + "\n"
                            + "세금코드: "
                            + taxCode
                            + "\n"
                            + "========================";

            System.out.println();
            System.out.println(output);

            System.out.println();
            System.out.println("===== OCR 전체 =====");
            System.out.println(ocrText);
            System.out.println("===================");

            return output;

        } catch (Exception e) {

            e.printStackTrace();

            return "CLOVA OCR 오류: " + e.getMessage();
        }
    }

    // =========================================================
    // 사업자번호
    // =========================================================

    private String extractBusinessNumber(String text) {

        Pattern pattern =
                Pattern.compile(
                        "(?<!\\d)(\\d{3})[-\\s]?(\\d{2})[-\\s]?(\\d{5})(?!\\d)"
                );

        Matcher matcher =
                pattern.matcher(text);

        if (matcher.find()) {

            return matcher.group(1)
                    + "-"
                    + matcher.group(2)
                    + "-"
                    + matcher.group(3);
        }

        return "찾지 못함";
    }

    // =========================================================
    // 면세금액
    // =========================================================

    private long extractTaxFreeAmount(String text) {

        String[] labels = {
                "면세물품가액",
                "면세품가액",
                "면세금액",
                "면세",
                "부가세면세물품가액",
                "부가세면세"
        };

        return findAmountNearLabel(
                text,
                labels
        );
    }

    // =========================================================
    // 부가세
    // =========================================================

    private long extractVatAmount(String text) {

        String[] labels = {
                "부가세",
                "부가가치세",
                "VAT",
                "V.A.T"
        };

        return findAmountNearLabel(
                text,
                labels
        );
    }

    // =========================================================
    // 총 결제금액
    // =========================================================

    private long extractTotalAmount(String text) {

        String[] labels = {
                "결제금액",
                "결제 금액",
                "승인금액",
                "카드승인금액",
                "받을금액",
                "받을 금액",
                "합계금액",
                "합계 금액",
                "총금액",
                "총 금액",
                "총액",
                "합계"
        };

        return findAmountNearLabel(
                text,
                labels
        );
    }

    // =========================================================
    // 특정 글자 근처에서 금액 찾기
    // =========================================================

    private long findAmountNearLabel(
            String text,
            String[] labels
    ) {

        String[] lines =
                text.split("\\R");

        for (int i = 0; i < lines.length; i++) {

            String line =
                    normalize(lines[i]);

            for (String label : labels) {

                String normalizedLabel =
                        normalize(label);

                if (line.contains(normalizedLabel)) {

                    // 같은 줄에 금액이 있는 경우
                    long amount =
                            extractMoney(lines[i]);

                    if (amount >= 0) {
                        return amount;
                    }

                    // OCR이 금액을 다음 줄로 분리한 경우
                    for (
                            int j = i + 1;
                            j < lines.length
                                    && j <= i + 3;
                            j++
                    ) {

                        amount =
                                extractMoney(lines[j]);

                        if (amount >= 0) {
                            return amount;
                        }
                    }
                }
            }
        }

        return 0;
    }

    // =========================================================
    // 한 줄에서 금액 찾기
    // =========================================================

    private long extractMoney(String line) {

        Pattern moneyPattern =
                Pattern.compile(
                        "(?<!\\d)(\\d{1,3}(?:,\\d{3})+|\\d{2,9})(?!\\d)"
                );

        Matcher matcher =
                moneyPattern.matcher(line);

        long largest =
                -1;

        while (matcher.find()) {

            String number =
                    matcher.group(1)
                            .replace(",", "");

            try {

                long value =
                        Long.parseLong(number);

                if (value > largest) {
                    largest = value;
                }

            } catch (NumberFormatException ignored) {
            }
        }

        return largest;
    }

    // =========================================================
    // V5 / ZZ 판별
    // =========================================================

    private String classifyTaxCode(
            long taxFreeAmount,
            long vatAmount
    ) {

        // 면세금액이 있으면 ZZ
        if (taxFreeAmount > 0) {
            return "ZZ";
        }

        // 면세가 없고 부가세가 있으면 V5
        if (vatAmount > 0) {
            return "V5";
        }

        // 나머지는 ZZ
        return "ZZ";
    }

    // =========================================================
    // 글자 비교 편하게 정리
    // =========================================================

    private String normalize(String text) {

        return text
                .replace(" ", "")
                .replace(":", "")
                .replace("：", "")
                .replace("[", "")
                .replace("]", "")
                .trim()
                .toUpperCase();
    }

    // =========================================================
    // 금액 표시
    // =========================================================

    private String formatMoney(long amount) {

        if (amount <= 0) {
            return "0원";
        }

        return String.format(
                "%,d원",
                amount
        );
    }

    // =========================================================
    // CLOVA 호출
    // =========================================================

    private String callClovaOcr(
            String apiUrl,
            String secretKey,
            String requestBody
    ) throws Exception {

        URL url =
                new URL(apiUrl);

        HttpURLConnection connection =
                (HttpURLConnection) url.openConnection();

        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setDoInput(true);

        connection.setConnectTimeout(30000);
        connection.setReadTimeout(60000);

        connection.setRequestProperty(
                "Content-Type",
                "application/json"
        );

        connection.setRequestProperty(
                "Accept",
                "application/json"
        );

        connection.setRequestProperty(
                "X-OCR-SECRET",
                secretKey
        );

        byte[] bodyBytes =
                requestBody.getBytes(
                        StandardCharsets.UTF_8
                );

        connection.setFixedLengthStreamingMode(
                bodyBytes.length
        );

        try (
                OutputStream outputStream =
                        connection.getOutputStream()
        ) {

            outputStream.write(bodyBytes);
            outputStream.flush();
        }

        int responseCode =
                connection.getResponseCode();

        InputStream responseStream;

        if (
                responseCode >= 200
                        && responseCode < 300
        ) {

            responseStream =
                    connection.getInputStream();

        } else {

            responseStream =
                    connection.getErrorStream();
        }

        String responseBody =
                readResponse(responseStream);

        connection.disconnect();

        if (
                responseCode < 200
                        || responseCode >= 300
        ) {

            throw new RuntimeException(
                    "HTTP "
                            + responseCode
                            + " / "
                            + responseBody
            );
        }

        return responseBody;
    }

    // =========================================================
    // OCR JSON에서 inferText만 꺼냄
    // =========================================================

    private String extractOcrText(String json) {

        StringBuilder text =
                new StringBuilder();

        Pattern pattern =
                Pattern.compile(
                        "\"inferText\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\""
                );

        Matcher matcher =
                pattern.matcher(json);

        while (matcher.find()) {

            String word =
                    unescapeJson(
                            matcher.group(1)
                    );

            text.append(word);
            text.append("\n");
        }

        if (text.length() == 0) {
            return "인식된 글자가 없습니다.";
        }

        return text.toString();
    }

    private String unescapeJson(String text) {

        return text
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "")
                .replace("\\t", "\t");
    }

    // =========================================================
    // HTTP 응답 읽기
    // =========================================================

    private String readResponse(
            InputStream inputStream
    ) throws Exception {

        if (inputStream == null) {
            return "";
        }

        StringBuilder result =
                new StringBuilder();

        try (
                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        inputStream,
                                        StandardCharsets.UTF_8
                                )
                        )
        ) {

            String line;

            while (
                    (line = reader.readLine())
                            != null
            ) {

                result.append(line);
            }
        }

        return result.toString();
    }

    // =========================================================
    // 이미지 확장자
    // =========================================================

    private String getImageFormat(
            MultipartFile receipt
    ) {

        String contentType =
                receipt.getContentType();

        if (
                "image/png"
                        .equalsIgnoreCase(contentType)
        ) {
            return "png";
        }

        return "jpg";
    }
}