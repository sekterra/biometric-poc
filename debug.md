[목적]
현재 프로젝트의 compileSdk 업그레이드에 따른 영향도를 분석하고 보고서를 작성한다.
실제 코드 수정은 하지 않는다. 분석 및 보고서 작성만 수행한다.

[현재 프로젝트 환경]
- AGP: 7.0.4
- Gradle: 7.2
- JDK: 11
- 모듈 구성:
    app               compileSdk=28 minSdk=23 targetSdk=28
    alopex_blaze      compileSdk=28 minSdk=23 targetSdk=28
    alopex_blaze_core compileSdk=28 minSdk=23 targetSdk=28
    blaze_app_init    compileSdk=28 minSdk=23 targetSdk=28
    blaze_app_db      compileSdk=28 minSdk=23 targetSdk=28
    blaze_extension   compileSdk=28 minSdk=23 targetSdk=28
    blaze_secure_extension compileSdk=28 minSdk=23 targetSdk=28
    CorsLib           compileSdk=28 minSdk=23 targetSdk=28
    connection        compileSdk=29 minSdk=23 targetSdk=29
- appcompat: appcompat-v7:28.0.0 + jetifier=true
- gson: local jar 2.2.4

[분석 목표]
androidx.security:security-crypto:1.1.0-alpha06 사용을 위해
compileSdk를 33으로 올릴 경우 발생하는 영향도 전체 분석

[분석 항목]

1. 빌드 도구 업그레이드 필요 여부
   - 현재 AGP 7.0.4 → compileSdk 33 지원을 위한 최소 AGP 버전
   - 현재 Gradle 7.2 → AGP 업그레이드에 따른 최소 Gradle 버전
   - JDK 11 유지 가능 여부
   - 각 업그레이드 항목의 필요 여부를 표로 정리:
     | 항목 | 현재 | 필요 버전 | 업그레이드 필요 여부 |

2. 모듈별 compileSdk 업그레이드 영향도
   - 각 모듈별로 compileSdk 28 → 33 변경 시:
     (1) 빌드 오류 가능성
     (2) 기존 코드 수정 필요 여부
     (3) deprecated API 사용 여부
   - 결과를 표로 정리:
     | 모듈명 | 영향도 | 예상 오류 | 조치 필요 여부 |

3. 의존성 충돌 분석
   - 현재 선언된 모든 의존성 목록 추출
   - compileSdk 33 환경에서 충돌 가능한 의존성 식별
   - appcompat-v7:28.0.0 + jetifier 구조에서 발생 가능한 충돌
   - gson local jar 2.2.4 충돌 가능성
   - 결과를 표로 정리:
     | 의존성 | 현재 버전 | 충돌 여부 | 권장 버전 |

4. API 변경사항 영향도
   - Android API 29~33 사이 변경된 API 중
     현재 프로젝트 코드에서 사용 중인 항목 식별
   - deprecated 항목 목록
   - 동작 변경(behavior change) 항목 목록
   - 결과를 표로 정리:
     | API | 변경 내용 | 영향 모듈 | 대응 방법 |

5. targetSdk 영향도
   - targetSdk는 28 유지 가능 여부 확인
   - compileSdk 33 + targetSdk 28 조합의 제약사항

6. 전체 위험도 평가
   - 전체 업그레이드 작업 규모 추정:
     | 항목 | 작업량 | 위험도 | 비고 |
   - 업그레이드 권장 순서 제시

[출력 형식]
- 파일명: upgrade-impact-analysis.md
- 저장 위치: 프로젝트 루트
- Markdown 형식
- 각 분석 항목별 결론을 명확히 제시
- 작업 완료 후 파일 경로 보고
