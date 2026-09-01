import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() => runApp(const BoliApp());

class BoliApp extends StatelessWidget {
  const BoliApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'Boli',
        theme: ThemeData(useMaterial3: true),
        home: const AsrPage(),
      );
}

class AsrPage extends StatefulWidget {
  const AsrPage({super.key});

  @override
  State<AsrPage> createState() => _AsrPageState();
}

class _AsrPageState extends State<AsrPage> {
  // The entire Dart<->Kotlin surface. Two methods, both returning String.
  static const _channel = MethodChannel('boli/asr');

  String _status = 'Ready — model loads on device, no network';
  String _transcript = '';
  bool _busy = false;

  Future<void> _run(String method, String working) async {
    setState(() {
      _busy = true;
      _status = working;
      _transcript = '';
    });
    try {
      final text = await _channel.invokeMethod<String>(method);
      setState(() {
        _status = 'Done';
        _transcript = (text ?? '').isEmpty ? '(no speech detected)' : text!;
      });
    } on PlatformException catch (e) {
      setState(() => _status = 'Failed: ${e.message}');
    } finally {
      setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Boli — offline Marathi ASR')),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            FilledButton(
              onPressed: _busy ? null : () => _run('transcribeAsset', 'Transcribing bundled sample…'),
              child: const Text('Transcribe sample.wav'),
            ),
            const SizedBox(height: 12),
            OutlinedButton(
              onPressed: _busy ? null : () => _run('transcribeMic', 'Recording 6 seconds — speak Marathi…'),
              child: const Text('Record and transcribe'),
            ),
            const SizedBox(height: 24),
            Text(_status, style: Theme.of(context).textTheme.bodySmall),
            const SizedBox(height: 12),
            if (_busy) const LinearProgressIndicator(),
            const SizedBox(height: 12),
            Expanded(
              child: SingleChildScrollView(
                child: SelectableText(
                  _transcript,
                  style: Theme.of(context).textTheme.headlineSmall,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
