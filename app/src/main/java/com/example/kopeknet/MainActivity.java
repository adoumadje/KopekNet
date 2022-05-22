package com.example.kopeknet;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.kopeknet.ml.Model;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class MainActivity extends AppCompatActivity {

    ImageView ivMainImage;
    TextView tvResult, tvConfidence;
    Button btnTakePhoto, btnUploadeImage, btnLearnMore;
    int TAKE_PHOTO_REQUEST = 100;
    int LOAD_IMAGE_REQUEST = 200;
    int IMG_SIZE = 224;
    String URL = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ActionBar actionBar = getSupportActionBar();
        actionBar.setTitle("KöpekNet");

        ivMainImage = findViewById(R.id.iv_MainImage);
        tvConfidence = findViewById(R.id.tv_Confidence);
        tvResult = findViewById(R.id.tv_Result);
        btnTakePhoto = findViewById(R.id.btn_TakePhoto);
        btnUploadeImage = findViewById(R.id.btn_UploadImage);
        btnLearnMore = findViewById(R.id.btn_LearnMore);

        btnLearnMore.setEnabled(false);

        btnTakePhoto.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // if we have permission launch camera
                if(ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED) {
                    Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                    startActivityForResult(intent, TAKE_PHOTO_REQUEST);
                } else {
                    // Request camera permission if we don't have it
                    ActivityCompat.requestPermissions(MainActivity.this,
                            new String[]{Manifest.permission.CAMERA}, 3);
                }
            }
        });
        btnUploadeImage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                startActivityForResult(intent, LOAD_IMAGE_REQUEST);
            }
        });
        btnLearnMore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(MainActivity.this, LearnMoreActivity.class);
                intent.putExtra("URL", URL);
                startActivity(intent);
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if(requestCode == TAKE_PHOTO_REQUEST && resultCode == Activity.RESULT_OK) {
            Bitmap image = (Bitmap) data.getExtras().get("data");
            int dimension = Math.min(image.getWidth(), image.getHeight());
            image = ThumbnailUtils.extractThumbnail(image, dimension, dimension);
            ivMainImage.setImageBitmap(image);
            image = Bitmap.createScaledBitmap(image, IMG_SIZE, IMG_SIZE, false);
            classifyImage(image);
        } else if (requestCode == LOAD_IMAGE_REQUEST && resultCode == Activity.RESULT_OK) {
            Uri pickedImage = data.getData();
            try {
                Bitmap image = MediaStore.Images.Media.getBitmap(getContentResolver(), pickedImage);
                int dimension = Math.min(image.getWidth(), image.getHeight());
                image = ThumbnailUtils.extractThumbnail(image, dimension, dimension);
                ivMainImage.setImageBitmap(image);
                image = Bitmap.createScaledBitmap(image, IMG_SIZE, IMG_SIZE, false);
                classifyImage(image);

            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            Toast.makeText(this, "Error loading image", Toast.LENGTH_SHORT).show();
        }
    }

    private void classifyImage(Bitmap image) {
        try {
            Model model = Model.newInstance(getApplicationContext());

            // Creates inputs for reference.
            TensorBuffer inputFeature0 = TensorBuffer.createFixedSize(new int[]{1, 224, 224, 3}, DataType.FLOAT32);
            ByteBuffer byteBuffer = ByteBuffer.allocateDirect(4 * IMG_SIZE * IMG_SIZE * 3);
            byteBuffer.order(ByteOrder.nativeOrder());

            int[] intValues = new int[IMG_SIZE * IMG_SIZE];
            image.getPixels(intValues, 0, image.getWidth(), 0, 0, image.getWidth(), image.getHeight());
            int pixel = 0;
            for(int i = 0; i < IMG_SIZE; ++i) {
                for (int j = 0; j < IMG_SIZE; ++j) {
                    int val = intValues[pixel++]; // RGB
                    byteBuffer.putFloat(((val >> 16) & 0xFF) * (1.f / 255.f));
                    byteBuffer.putFloat(((val >> 8) & 0xFF) * (1.f / 255.f));
                    byteBuffer.putFloat((val & 0xFF) * (1.f / 255.f));
                }
            }

            inputFeature0.loadBuffer(byteBuffer);

            // Runs model inference and gets result.
            Model.Outputs outputs = model.process(inputFeature0);
            TensorBuffer outputFeature0 = outputs.getOutputFeature0AsTensorBuffer();

            float[] confidences = outputFeature0.getFloatArray();
            int maxPos = 0;
            float maxConfidence = 0f;
            for(int i = 0; i < confidences.length; ++i) {
                if(confidences[i] > maxConfidence) {
                    maxConfidence = confidences[i];
                    maxPos = i;
                }
            }
            String[] classes = getModelClasses();

            String predictedClass = classes[maxPos].replace('_', ' ');
            tvResult.setText(predictedClass);
            String predConfidence = String.format("%.1f%%", confidences[maxPos] * 100);
            tvConfidence.setText(predConfidence);
            URL = "https://en.wikipedia.org/wiki/"+classes[maxPos];
            btnLearnMore.setEnabled(true);

            // Releases model resources if no longer used.
            model.close();
        } catch (IOException e) {
            // TODO Handle the exception
        }
    }

    private String[] getModelClasses() {
        String text = "";
        try {
            InputStream inputStream = getAssets().open("labels.txt");
            int SIZE = inputStream.available();
            byte[] buffer = new byte[SIZE];
            inputStream.read(buffer);
            inputStream.close();
            text = new String(buffer);
        } catch (IOException e) {
            e.printStackTrace();
        }
        String[] labelsArr = text.split("\n");
        int index;
        for(int i = 0; i < labelsArr.length; ++i) {
            index = labelsArr[i].indexOf(' ');
            labelsArr[i] = labelsArr[i].substring(++index);
        }
        return labelsArr;
    }
}