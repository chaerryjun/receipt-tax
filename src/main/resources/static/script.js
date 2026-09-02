const receipt = document.getElementById("receipt");
const analyzeBtn = document.getElementById("analyzeBtn");
const result = document.getElementById("result");

analyzeBtn.addEventListener("click", async function (event) {

    event.preventDefault();

    if (receipt.files.length === 0) {
        result.innerText = "영수증 사진을 선택해주세요.";
        return;
    }

    result.innerText = "사진 전송 중...";

    const formData = new FormData();
    formData.append("receipt", receipt.files[0]);

    try {
        const response = await fetch("/api/analyze", {
            method: "POST",
            body: formData
        });

        const text = await response.text();

        result.innerText = text;

    } catch (error) {
        result.innerText = "서버 연결 실패";
        console.error(error);
    }
});