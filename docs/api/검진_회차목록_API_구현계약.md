# 검진 회차 목록 API 구현 계약

<!-- 작성자: 김진우 -->

기존 `HEAPY_BACKEND_API_명세_v1.md`의 2.5 공통 페이지네이션 및 `GET /api/checkups`를 구현한다. 기존 앱과 상세 조회 계약을 유지하며 DB 마이그레이션은 없다.

## 요청

`GET /api/checkups?limit=100`, Bearer 인증 필수. 본인 회차만 조회한다.

- `limit`: 생략 시 20, 정수 1~100.
- `cursor`: 생략 시 첫 페이지. 서버가 발급한 불투명 문자열, 최대 256자. 클라이언트는 해석하거나 생성하지 않는다.
- 잘못된 제한·빈 문자열 커서·잘못된 커서·다른 사용자 커서는 400 `COMMON-001`. 인증 실패는 기존 인증 계약을 따른다.

## 응답

```json
{
  "success": true,
  "data": [
    {
      "recordId": "4076edb6-c9bc-403b-b951-d287f78c609d",
      "measuredAt": "2026-08-12",
      "providerName": "합성 검진센터",
      "sourceType": "ocr",
      "resultCount": 0,
      "confirmedAt": "2026-08-12T01:00:00Z"
    }
  ],
  "message": "건강검진 목록을 조회했습니다.",
  "meta": {"nextCursor": null, "hasNext": false, "limit": 100}
}
```

- `data`는 배열이며 빈 결과는 200과 빈 배열이다. 앱의 기존 `getCheckups()`와 호환된다.
- `measuredAt`, `providerName`, `confirmedAt`은 기존 기록에서 누락되었으면 null이다. 날짜·기관·확정 시각을 추정하지 않는다.
- `resultCount`는 일반 검사 결과 행 수다. 소견 전용 회차도 0으로 목록에 포함하며 소견은 기존 상세 API에서 조회한다.
- 정렬은 `measuredAt DESC NULLS LAST, recordId DESC`. 검진일 없는 기록은 마지막이며 같은 검진일에는 회차 ID로 순서를 고정한다.
- 임시 OCR 작업은 조회하지 않고 정식 `health_checkup_records`만 조회한다. 과거 이관 기록의 `confirmedAt`이 null이어도 제외하지 않는다.
- 다음 페이지가 없으면 `nextCursor=null`, `hasNext=false`. 응답은 `Cache-Control: no-store`다.
- 기존 앱은 최대 100건을 표시한다. 100건 초과 페이지 탐색 UI는 이번 백엔드 작업에 포함하지 않는다.

## 참조

백엔드에 `Reference/rule`은 없다. 실제 대조 문서는 백엔드 `docs/api/HEAPY_BACKEND_API_명세_v1.md`, `docs/requirements/HEAPY_화면검토_확정결과.md`, `C:/Users/jinwo/heapy-ai-health/Reference/HEAPY_전체_시스템_아키텍처_v1.md`, `HEAPY_DB_물리설계_v1.md`다. 날짜 누락 순서와 커서 오류는 이 문서에서 구체화했다.
