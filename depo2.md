// onLoginError에 REGISTER_COMPLETE 분기 추가
function onLoginError(errorCode) {
    console.warn('onLoginError', errorCode);

    switch(errorCode) {
        case 'REGISTER_COMPLETE':
            // 등록 완료 안내 후 사용자가 다시 로그인 버튼 클릭
            alert('등록이 완료되었습니다. 안면인식 로그인 버튼을 다시 클릭해주세요.');
            break;
        case 'ACCOUNT_LOCKED':
            alert('계정이 잠겼습니다. ID/PW로 잠금을 해제해주세요.');
            break;
        case 'EMPTY_USER_ID':
            alert('사용자 ID를 입력해주세요.');
            break;
        case 'NETWORK_ERROR':
            alert('네트워크 연결을 확인해주세요.');
            break;
        default:
            alert('오류가 발생했습니다: ' + errorCode);
            break;
    }
}
