FROM python:3.6
ADD . /app
WORKDIR /app

RUN pip install -r requirements.txt
EXPOSE 80
CMD ["gunicorn", "-b", "0.0.0.0:80", "app"]