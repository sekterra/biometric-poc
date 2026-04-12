[목적]
현재 프로젝트(A2)의 구조를 분석하여
외부 생체인증 기능을 이식할 최적 위치를 파악한다.
실제 코드 수정은 하지 않는다. 분석 및 보고만 수행한다.

[분석 대상]
alopex_blaze 모듈의 아래 클래스들:
- DefaultAlopexWebViewScreen
- AbstractScreen
- WebViewHandler (DefaultAlopexWebViewScreen의 inner class)
- AlopexWebView

[알려진 구조 정보]
- DefaultAlopexWebViewScreen → AbstractScreen → AppCompatActivity 상속
- WebViewHandler: DefaultAlopexWebViewScreen의 inner class (상속 없음)
- WebViewHandler 속성: Stack<Pair<PageNavi, AlopexWebView>> mWebViews
- AlopexWebView: WebView 상속

[분석 요청 사항]

1. DefaultAlopexWebViewScreen 분석
   - onCreate() 또는 초기화 시점 전체 흐름
   - WebViewHandler 생성 시점
   - login.html 로드 시점 및 방법
   - AlopexWebView 생성 시점

2. WebViewHandler 분석
   - mWebViews Stack 관리 방식
   - 현재 활성 AlopexWebView 접근 방법
   - addJavascriptInterface 호출 가능 시점

3. AlopexWebView 분석
   - 생성자 파라미터
   - 초기화 시점에 추가 설정 가능 여부

4. 이식 위치 후보 분석
   아래 3가지 후보 각각에 대해
   가능 여부와 이유를 명확히 판단:

   후보 A: DefaultAlopexWebViewScreen에서
           AndroidBridge 생성 후
           AlopexWebView에 addJavascriptInterface 주입
           (조건: FragmentActivity 참조 확보 필요)

   후보 B: WebViewHandler 내부에서
           outer class(DefaultAlopexWebViewScreen) 참조로
           AndroidBridge 생성 후 주입
           (조건: inner class이므로 outer class 접근 가능 여부 확인)

   후보 C: AlopexWebView 생성자 또는 초기화 시점에서 처리
           (조건: FragmentActivity 참조 전달 가능 여부 확인)

5. 결과 보고 형식
   아래 항목을 반드시 포함:

   [클래스별 초기화 흐름]
   DefaultAlopexWebViewScreen.onCreate()
       └── 1. ...
       └── 2. ...
       └── 3. login.html 로드 시점

   [추천 이식 위치]
   - 후보: A / B / C 중 선택
   - 이유: 구체적 근거 명시
   - addJavascriptInterface 추가 위치 코드 예시:
     (수정 아님, 위치만 표시)

   [주의사항]
   - FragmentActivity 참조 관련
   - 기존 WebView 동작 영향 여부
   - login.html 로드 순서와의 관계

[제약 조건]
- 실제 코드 수정 금지
- alopex_blaze 모듈 build.gradle 수정 금지
- 기존 WebView 동작 유지 필수
- AndroidBridge 생성자는 반드시 FragmentActivity를 받아야 함
