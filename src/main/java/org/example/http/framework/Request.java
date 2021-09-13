package org.example.http.framework;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class Request {
  String method;
  String path;
  Map<String, String> headers;
  Map<String,List<String>> query;
  Map<String, List<String>> form;
  @Builder.Default
  byte[] body = new byte[]{};
}
