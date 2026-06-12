import base64
import json
import logging
from typing import Dict, Any

from werkzeug.test import EnvironBuilder

from app import application

# Configure logging for Lambda
logger = logging.getLogger()
logger.setLevel(logging.INFO)


def handler(event: Dict[str, Any], context: Any) -> Dict[str, Any]:
    """
    Lambda handler function for API Gateway proxy integration.

    Args:
        event: API Gateway proxy event
        context: Lambda context object

    Returns:
        API Gateway proxy response
    """
    try:
        # Health check endpoint
        if event.get("path") == "/health":
            return {
                "statusCode": 200,
                "headers": {
                    "Content-Type": "application/json",
                    "Access-Control-Allow-Origin": "*",
                },
                "body": json.dumps(
                    {
                        "status": "healthy",
                        "service": "bgg-game-selector-api",
                        "version": "2.0",
                    }
                ),
            }

        # Extract request details from API Gateway event
        http_method = event.get("httpMethod", "GET")
        path = event.get("path", "/")
        headers = event.get("headers", {})
        query_params = event.get("queryStringParameters") or {}

        raw_body = event.get("body") or ""
        if event.get("isBase64Encoded"):
            body = base64.b64decode(raw_body)
        else:
            body = raw_body.encode("utf-8") if isinstance(raw_body, str) else raw_body

        content_type = (headers or {}).get(
            "Content-Type", headers.get("content-type", "")
        )

        builder = EnvironBuilder(
            method=http_method,
            path=path,
            query_string=query_params,
            headers=headers,
            data=body,
            content_type=content_type,
        )
        environ = builder.get_environ()

        with application.request_context(environ):
            try:
                response = application.full_dispatch_request()
            except Exception as e:
                logger.error(f"Error processing request: {e}", exc_info=True)
                return {
                    "statusCode": 500,
                    "headers": {
                        "Content-Type": "application/json",
                        "Access-Control-Allow-Origin": "*",
                    },
                    "body": json.dumps(
                        {"error": "Internal server error", "message": str(e)}
                    ),
                }

        # Extract response details
        status_code = response.status_code
        response_headers = dict(response.headers)
        response_headers["Access-Control-Allow-Origin"] = "*"

        content_type = response_headers.get("Content-Type", "")
        is_binary = not (
            content_type.startswith("text/")
            or "json" in content_type
            or "xml" in content_type
            or "javascript" in content_type
        )

        if is_binary:
            body = base64.b64encode(response.get_data()).decode("utf-8")
        else:
            body = response.get_data(as_text=True)

        return {
            "statusCode": status_code,
            "headers": response_headers,
            "body": body,
            "isBase64Encoded": is_binary,
        }

    except Exception as e:
        logger.error(f"Unexpected error in Lambda handler: {e}", exc_info=True)
        return {
            "statusCode": 500,
            "headers": {
                "Content-Type": "application/json",
                "Access-Control-Allow-Origin": "*",
            },
            "body": json.dumps(
                {
                    "error": "Internal server error",
                    "message": "An unexpected error occurred",
                }
            ),
        }
