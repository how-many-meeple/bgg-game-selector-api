FROM python:3.6
ADD . /app
WORKDIR /app

RUN pip install -r requirements.txt
EXPOSE 8080
CMD ["gunicorn", "-k", "gevent", "-w", "3", "-b", "0.0.0.0:8080", "app"]