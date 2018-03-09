import React, { Component } from 'react';
import {
    View,
    Text,
    Button,
    Platform,
    TouchableOpacity,
    Linking,
    TextInput,
} from 'react-native';
import NfcManager, {NdefParser} from 'react-native-nfc-manager';

class App extends Component {
    constructor(props) {
        super(props);
        this.state = {
            supported: true,
            enabled: false,
            isWriting: false,
            urlToWrite: 'google.com',
            tag: {},
        }
    }

    componentDidMount() {
        NfcManager.isSupported()
            .then(supported => {
                this.setState({ supported });
                if (supported) {
                    this._startNfc();
                }
            })
    }

    render() {
        let { supported, enabled, tag, isWriting, urlToWrite} = this.state;
        return (
            <View style={{ flex: 1, justifyContent: 'center', alignItems: 'center' }}>
                <Text>{`Is NFC supported ? ${supported}`}</Text>
                <Text>{`Is NFC enabled (Android only)? ${enabled}`}</Text>

                <TouchableOpacity style={{ marginTop: 20 }} onPress={this._startDetection}>
                    <Text style={{ color: 'blue' }}>Start Tag Detection</Text>
                </TouchableOpacity>

                <TouchableOpacity style={{ marginTop: 20 }} onPress={this._stopDetection}>
                    <Text style={{ color: 'red' }}>Stop Tag Detection</Text>
                </TouchableOpacity>

                <TouchableOpacity style={{ marginTop: 20 }} onPress={this._clearMessages}>
                    <Text>Clear</Text>
                </TouchableOpacity>

                <TouchableOpacity style={{ marginTop: 20 }} onPress={this._goToNfcSetting}>
                    <Text >(android) Go to NFC setting</Text>
                </TouchableOpacity>

                {
                    <View style={{padding: 10, marginTop: 20, backgroundColor: '#e0e0e0'}}>
                        <Text>(android) Write NDEF Test</Text>
                        <View style={{ flexDirection: 'row', alignItems: 'center' }}>
                            <Text>http://www.</Text>
                            <TextInput
                                style={{width: 200}}
                                value={urlToWrite}
                                onChangeText={urlToWrite => this.setState({ urlToWrite })}
                            />
                        </View>

                        <TouchableOpacity 
                            style={{ marginTop: 20, borderWidth: 1, borderColor: 'blue', padding: 10 }} 
                            onPress={isWriting ? this._cancelNdefWrite : this._requestNdefWrite}>
                            <Text style={{color: 'blue'}}>{`(android) ${isWriting ? 'Cancel' : 'Write NDEF'}`}</Text>
                        </TouchableOpacity>
                    </View>
                }

                <Text style={{ marginTop: 20 }}>{`Current tag JSON: ${JSON.stringify(tag)}`}</Text>
            </View>
        )
    }

    _requestNdefWrite = () => {
        function strToBytes(str) {
            let result = [];
            for (let i = 0; i < str.length; i++) {
                result.push(str.charCodeAt(i));
            }
            return result;
        }

        let {isWriting, urlToWrite} = this.state;
        if (isWriting) {
            return;
        }

        const urlBytes = strToBytes(urlToWrite);
        const headerBytes = [0xD1, 0x01, (urlBytes.length + 1), 0x55, 0x01];
        const bytes = [...headerBytes, ...urlBytes];

        this.setState({isWriting: true});
        NfcManager.requestNdefWrite(bytes)
            .then(() => console.log('write completed'))
            .catch(err => console.warn(err))
            .then(() => this.setState({isWriting: false}));
    }

    _cancelNdefWrite = () => {
        this.setState({isWriting: false});
        NfcManager.cancelNdefWrite()
            .then(() => console.log('write cancelled'))
            .catch(err => console.warn(err))
    }

    _startNfc() {
        NfcManager.start({
            onSessionClosedIOS: () => {
                console.log('ios session closed');
            }
        })
            .then(result => {
                console.log('start OK', result);
            })
            .catch(error => {
                console.warn('start fail', error);
                this.setState({supported: false});
            })

        if (Platform.OS === 'android') {
            NfcManager.getLaunchTagEvent()
                .then(tag => {
                    console.log('launch tag', tag);
                    if (tag) {
                        this.setState({ tag });
                    }
                })
                .catch(err => {
                    console.log(err);
                })
            NfcManager.isEnabled()
                .then(enabled => {
                    this.setState({ enabled });
                })
                .catch(err => {
                    console.log(err);
                })
        }
    }

    _onTagDiscovered = tag => {
        console.log('Tag Discovered', tag);
        this.setState({ tag });
        let url = this._parseUri(tag);
        if (url) {
            Linking.openURL(url)
                .catch(err => {
                    console.warn(err);
                })
        }
    }

    _startDetection = () => {
        NfcManager.registerTagEvent(this._onTagDiscovered)
            .then(result => {
                console.log('registerTagEvent OK', result)
            })
            .catch(error => {
                console.warn('registerTagEvent fail', error)
            })
    }

    _stopDetection = () => {
        NfcManager.unregisterTagEvent()
            .then(result => {
                console.log('unregisterTagEvent OK', result)
            })
            .catch(error => {
                console.warn('unregisterTagEvent fail', error)
            })
    }

    _clearMessages = () => {
        this.setState({tag: null});
    }

    _goToNfcSetting = () => {
        if (Platform.OS === 'android') {
            NfcManager.goToNfcSetting()
                .then(result => {
                    console.log('goToNfcSetting OK', result)
                })
                .catch(error => {
                    console.warn('goToNfcSetting fail', error)
                })
        }
    }

    _parseUri = (tag) => {
        let result = NdefParser.parseUri(tag.ndefMessage[0]),
            uri = result && result.uri;
        if (uri) {
            console.log('parseUri: ' + uri);
            return uri;
        }
        return null;
    }
}

export default App;
