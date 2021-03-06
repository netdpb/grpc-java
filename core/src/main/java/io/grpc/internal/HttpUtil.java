/*
 * Copyright 2014, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.internal;

import io.grpc.Metadata;
import io.grpc.Status;

import java.net.HttpURLConnection;

import javax.annotation.Nullable;

/**
 * Constants for GRPC-over-HTTP (or HTTP/2).
 */
public final class HttpUtil {

  /**
   * {@link io.grpc.Metadata.Key} for the Content-Type request/response header.
   */
  public static final Metadata.Key<String> CONTENT_TYPE_KEY =
          Metadata.Key.of("content-type", Metadata.ASCII_STRING_MARSHALLER);

  /**
   * {@link io.grpc.Metadata.Key} for the Content-Type request/response header.
   */
  public static final Metadata.Key<String> USER_AGENT_KEY =
          Metadata.Key.of("user-agent", Metadata.ASCII_STRING_MARSHALLER);

  /**
   * Content-Type used for GRPC-over-HTTP/2.
   */
  public static final String CONTENT_TYPE_GRPC = "application/grpc";

  /**
   * The HTTP method used for GRPC requests.
   */
  public static final String HTTP_METHOD = "POST";

  /**
   * The TE (transport encoding) header for requests over HTTP/2.
   */
  public static final String TE_TRAILERS = "trailers";

  /**
   * The Timeout header name.
   */
  public static final String TIMEOUT = "grpc-timeout";

  /**
   * The message encoding (i.e. compression) that can be used in the stream.
   */
  public static final String MESSAGE_ENCODING = "grpc-encoding";

  /**
   * Maps HTTP error response status codes to transport codes.
   */
  public static Status httpStatusToGrpcStatus(int httpStatusCode) {
    // Specific HTTP code handling.
    switch (httpStatusCode) {
      case HttpURLConnection.HTTP_UNAUTHORIZED:  // 401
        return Status.UNAUTHENTICATED;
      case HttpURLConnection.HTTP_FORBIDDEN:  // 403
        return Status.PERMISSION_DENIED;
      default:
    }
    // Generic HTTP code handling.
    if (httpStatusCode < 100) {
      // 0xx. These don't exist.
      return Status.UNKNOWN;
    }
    if (httpStatusCode < 200) {
      // 1xx. These headers should have been ignored.
      return Status.INTERNAL;
    }
    if (httpStatusCode < 300) {
      // 2xx
      return Status.OK;
    }
    return Status.UNKNOWN;
  }

  /**
   * All error codes identified by the HTTP/2 spec.
   */
  public enum Http2Error {
    NO_ERROR(0x0, Status.INTERNAL),
    PROTOCOL_ERROR(0x1, Status.INTERNAL),
    INTERNAL_ERROR(0x2, Status.INTERNAL),
    FLOW_CONTROL_ERROR(0x3, Status.INTERNAL),
    SETTINGS_TIMEOUT(0x4, Status.INTERNAL),
    STREAM_CLOSED(0x5, Status.INTERNAL),
    FRAME_SIZE_ERROR(0x6, Status.INTERNAL),
    REFUSED_STREAM(0x7, Status.UNAVAILABLE),
    CANCEL(0x8, Status.CANCELLED),
    COMPRESSION_ERROR(0x9, Status.INTERNAL),
    CONNECT_ERROR(0xA, Status.INTERNAL),
    ENHANCE_YOUR_CALM(0xB, Status.RESOURCE_EXHAUSTED.withDescription("Bandwidth exhausted")),
    INADEQUATE_SECURITY(0xC, Status.PERMISSION_DENIED.withDescription("Permission denied as "
        + "protocol is not secure enough to call")),
    HTTP_1_1_REQUIRED(0xD, Status.UNKNOWN);

    // Populate a mapping of code to enum value for quick look-up.
    private static final Http2Error[] codeMap;
    static {
      Http2Error[] errors = Http2Error.values();
      int size = (int) errors[errors.length - 1].code() + 1;
      codeMap = new Http2Error[size];
      for (Http2Error error : errors) {
        int index = (int) error.code();
        codeMap[index] = error;
      }
    }

    private final int code;
    private final Status status;

    Http2Error(int code, Status status) {
      this.code = code;
      this.status = status.augmentDescription("HTTP/2 error code: " + this.name());
    }

    /**
     * Gets the code for this error used on the wire.
     */
    public long code() {
      return code;
    }

    /**
     * Gets the {@link Status} associated with this HTTP/2 code.
     */
    public Status status() {
      return status;
    }

    /**
     * Looks up the HTTP/2 error code enum value for the specified code.
     *
     * @param code an HTTP/2 error code value.
     * @return the HTTP/2 error code enum or {@code null} if not found.
     */
    public static Http2Error forCode(long code) {
      if (code >= codeMap.length || code < 0) {
        return null;
      }
      return codeMap[(int) code];
    }

    /**
     * Looks up the {@link Status} from the given HTTP/2 error code. This is preferred over {@code
     * forCode(code).status()}, to more easily conform to HTTP/2:
     *
     * <blockquote>Unknown or unsupported error codes MUST NOT trigger any special behavior.
     * These MAY be treated by an implementation as being equivalent to INTERNAL_ERROR.</blockquote>
     *
     * @param code the HTTP/2 error code.
     * @return a {@link Status} representing the given error.
     */
    public static Status statusForCode(long code) {
      Http2Error error = forCode(code);
      if (error == null) {
        // This "forgets" the message of INTERNAL_ERROR while keeping the same status code.
        Status.Code statusCode = INTERNAL_ERROR.status().getCode();
        return Status.fromCodeValue(statusCode.value())
            .withDescription("Unrecognized HTTP/2 error code: " + code);
      }

      return error.status();
    }
  }

  /**
   * Gets the User-Agent string for the gRPC transport.
   */
  public static String getGrpcUserAgent(String transportName,
                                        @Nullable String applicationUserAgent) {
    StringBuilder builder = new StringBuilder("grpc-java-").append(transportName);
    String version = HttpUtil.class.getPackage().getImplementationVersion();
    if (version != null) {
      builder.append("/");
      builder.append(version);
    }
    if (applicationUserAgent != null) {
      builder.append(' ');
      builder.append(applicationUserAgent);
    }
    return builder.toString();
  }

  private HttpUtil() {}
}
