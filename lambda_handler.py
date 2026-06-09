"""
AWS Lambda handler for BGG Game Selector API.

Wraps the Flask application for serverless deployment.
"""
import json
import logging
from typing import Dict, Any

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
        if event.get('path') == '/health':
            return {
                'statusCode': 200,
                'headers': {
                    'Content-Type': 'application/json',
                    'Access-Control-Allow-Origin': '*'
                },
                'body': json.dumps({
                    'status': 'healthy',
                    'service': 'bgg-game-selector-api',
                    'version': '2.0'
                })
            }

        # Extract request details from API Gateway event
        http_method = event.get('httpMethod', 'GET')
        path = event.get('path', '/')
        headers = event.get('headers', {})
        query_params = event.get('queryStringParameters') or {}

        # Create WSGI-compatible environ for Flask
        from werkzeug.datastructures import EnvironHeaders
        from werkzeug.test import EnvironBuilder

        builder = EnvironBuilder(
            method=http_method,
            path=path,
            query_string=query_params,
            headers=headers
        )
        environ = builder.get_environ()

        # Process request through Flask application
        with application.request_context(environ):
            try:
                response = application.full_dispatch_request()
            except Exception as e:
                logger.error(f"Error processing request: {e}", exc_info=True)
                return {
                    'statusCode': 500,
                    'headers': {
                        'Content-Type': 'application/json',
                        'Access-Control-Allow-Origin': '*'
                    },
                    'body': json.dumps({
                        'error': 'Internal server error',
                        'message': str(e)
                    })
                }

        # Extract response details
        status_code = response.status_code
        response_headers = dict(response.headers)
        response_body = response.get_data(as_text=True)

        # Ensure CORS headers
        response_headers['Access-Control-Allow-Origin'] = '*'

        return {
            'statusCode': status_code,
            'headers': response_headers,
            'body': response_body
        }

    except Exception as e:
        logger.error(f"Unexpected error in Lambda handler: {e}", exc_info=True)
        return {
            'statusCode': 500,
            'headers': {
                'Content-Type': 'application/json',
                'Access-Control-Allow-Origin': '*'
            },
            'body': json.dumps({
                'error': 'Internal server error',
                'message': 'An unexpected error occurred'
            })
        }
