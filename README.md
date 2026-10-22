# Binance Phone Scanner (Android, no server)

안드로이드 휴대폰에서 바이낸스 USDT 무기한 선물 종목을 직접 검사하는 앱입니다. 별도 서버나 Binance API 키가 필요하지 않습니다. 휴대폰의 Wi-Fi 또는 모바일 데이터를 사용합니다.

## 감시 조건

한국 시간 기준 매시간 `HH:50`에 전체 거래 가능 USDT 무기한 선물 종목을 검사합니다.

1. 현재 진행 중인 1시간봉의 **고가가 시가 대비 +5% 이상**
2. 현재 진행 중인 1시간봉의 **현재가가 시가 대비 +4% 이상**
3. 현재 진행 중인 1시간봉의 누적 거래량이 **직전 완료된 1시간봉 거래량의 4.5배 이상**

세 조건을 모두 만족해야 감지합니다. EMA 조건은 사용하지 않습니다. 미완성 봉의 고가·현재가·누적 거래량을 사용하므로 봉이 마감되기 전에는 조건이 달라질 수 있습니다. 거래량은 Binance Kline API의 기초자산 수량(base-asset volume)입니다.

## 앱 기능

- 글래스모피즘 스타일의 어두운 UI
- 매시간 50분 검사
- 화면을 닫아도 실행되는 foreground service 및 상시 알림
- 최근 검사 시각, 검사한 종목 수, 조건 충족 종목 수, 실패 수 표시
- 실시간 검사 진행률 표시: 완료 종목 수 / 전체 종목 수 및 퍼센트
- 최근 검사 기록만 간단히 저장 (최근 5개 기록)
- 조건 충족 종목별 현재가, 고가 상승률, 현재가 상승률, 거래량 배수 표시
- 종목별 **거래하기** 버튼으로 바이낸스 선물 차트 열기
- 감지 시 휴대폰 알림
- 자동 주문/매매는 하지 않음

## GitHub에서 APK 만들기

이 프로젝트에는 GitHub Actions 빌드 워크플로가 포함되어 있습니다.

1. ZIP 파일을 PC에서 압축 해제합니다.
2. GitHub 저장소 `https://github.com/tnehdl02/binanace`를 엽니다.
3. 수정할 파일들을 저장소의 같은 경로에 업로드/교체합니다. `app` 폴더, `README.md` 등 프로젝트 파일이 저장소 루트에 있어야 합니다.
4. `.github/workflows/build-apk.yml`도 포함되어 있는지 확인합니다. 브라우저에서 숨김 폴더가 보이지 않으면 해당 파일은 GitHub에서 직접 경로를 만들어 업로드해야 할 수 있습니다.
5. 변경 사항을 `main` 브랜치에 커밋합니다.
6. 저장소의 **Actions** 탭에서 **Build Android APK** 워크플로가 성공했는지 확인합니다.
7. 성공한 실행을 열고 **Artifacts**에서 `binance-phone-scanner-debug-apk`를 다운로드합니다.
8. 아티팩트 ZIP을 풀고 `app-debug.apk`를 휴대폰에 옮겨 설치합니다.
9. 앱에서 알림 권한을 허용하고 **감시 시작**을 누릅니다.

APK는 테스트용 debug APK입니다. Play 스토어 배포용 서명 APK가 아닙니다.

## 사용 및 주의사항

- 감시를 시작하면 상시 알림이 표시됩니다. 앱 또는 알림의 `감시 중지`로 중지할 수 있습니다.
- 감지 종목이 없으면 앱 로그에 `조건 충족 종목 없음`으로 남습니다. 검사 실패는 성공한 검사와 구분해 기록합니다. 검사 중에는 완료된 코인 수와 퍼센트가 갱신됩니다.
- Android 설정에서 앱 배터리 사용을 `제한 없음` 또는 `최적화 제외`로 설정하면 백그라운드 유지에 도움이 됩니다. 메뉴 이름은 기기마다 다를 수 있습니다.
- 강제 종료, 재부팅, 인터넷 단절, 제조사별 배터리 관리 또는 Binance API 제한이 발생하면 감시가 중단되거나 일부 종목 검사가 실패할 수 있습니다. 이 앱은 24시간 무중단 실행을 보장하지 않습니다.
- 재부팅 후에는 앱을 다시 열어 감시를 시작해야 합니다.
- 이 앱은 공개 시세를 읽을 뿐 주문을 실행하지 않습니다.
\n\n## v5 UI/diagnostic patch\n- Start/stop buttons show request state and catch startup exceptions.\n- Screen refresh errors are displayed in the status card instead of silently crashing the refresh loop.\n- Scanner service records an executor/startup failure to logs and resets the running flag.\n\n\n## v6 screen recovery patch\n- MainActivity catches startup/UI construction errors and shows a diagnostic screen instead of remaining blank.\n- The ScrollView is attached before building the remaining views to reduce blank-screen risk.\n- Not yet validated with a GitHub Actions build or on a physical device.\n

## 진행률 표시 업데이트
- 검사 완료 건수와 전체 종목 수를 공유 환경설정에 기록하고 화면의 가로 진행 막대 및 퍼센트를 갱신합니다.
- 화면 로그는 최근 5개 기록만 유지하도록 제한했습니다.
- 이 ZIP은 소스 변경본이며, GitHub Actions에서 빌드 성공 여부를 확인해야 합니다. 실제 기기에서의 테스트는 아직 수행하지 않았습니다.
