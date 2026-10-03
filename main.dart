import 'package:flutter/material.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const DocPdfApp());
}

class DocPdfApp extends StatelessWidget {
  const DocPdfApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Đọc PDF nét',
      debugShowCheckedModeBanner: false,
      home: Scaffold(
        body: SafeArea(
          child: InAppWebView(
            initialFile: 'assets/index.html',
            initialSettings: InAppWebViewSettings(
              javaScriptEnabled: true,
              domStorageEnabled: true,
              allowFileAccess: true,
              allowFileAccessFromFileURLs: true,
              allowUniversalAccessFromFileURLs: true,
              supportZoom: true,
              builtInZoomControls: true,
              displayZoomControls: false,
            ),
          ),
        ),
      ),
    );
  }
}
