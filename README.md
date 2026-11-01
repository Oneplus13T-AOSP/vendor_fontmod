# FontMod

PixelOS Android 17용 독립 사전탑재 폰트 관리 앱입니다.
이 저장소는 ROM의 `vendor/fontmod`에 별도 프로젝트로 둡니다.
기존 vendor/custom 프로젝트 안에 합치지 않습니다.

- 흰 배경 / 페르시안 블루, PixelOS 기본 폰트로 시작.
- TTF/OTF 추가 시 이름 지정, 다중 보관, 문장 미리보기, 단일·복수 삭제.
- 사전탑재 폰트 없음. PixelOS 기본값 또는 앱에서 직접 추가한 파일만 사용.
- platform 서명·product privileged 설치, UPDATE_FONTS 권한 사용.
- 시스템 적용·복원에는 extraPatches의 FontMod framework/init/SELinux 패치가 필요합니다.
- vendor/custom 공통 제품 설정에서 `vendor/fontmod/fontmod.mk`를 상속합니다.

빌드는 사용자가 전체 ROM 빌드로 수행합니다. 개별 모듈 빌드는 사용하지 않습니다.
아직 수정된 ROM의 컴파일·부팅·재부팅 없는 실제 폰트 적용은 검증하지 않았습니다.

PixelOS 기본 폰트 파일을 별도로 복사하거나 재등록하지 않습니다.
사용자 폰트의 시스템 적용을 해제하는 ‘PixelOS 기본값으로 복원’ 기능으로 원래 폰트로 돌아갑니다.
Evolution X 폰트도 필요하면 사용자가 앱에서 직접 파일을 추가합니다.

이 앱 프로젝트의 remote와 repo manifest는 지정된 원격 저장소가 없어 등록하지 않았습니다.
현재 로컬 소스는 별도 Git 저장소로 관리하며, extraPatches에는 앱 소스를 중복 저장하지 않습니다.
