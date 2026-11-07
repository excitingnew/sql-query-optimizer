SELECT ime, prosek
FROM Student
WHERE smer = 'RTI'

java -jar baze-kt-fat.jar --sema primeri/input.json --upit "SELECT ime FROM Student"