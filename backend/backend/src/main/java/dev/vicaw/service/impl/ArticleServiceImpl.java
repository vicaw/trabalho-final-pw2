package dev.vicaw.service.impl;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.validation.Valid;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.core.Response;

import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import com.github.slugify.Slugify;

import dev.vicaw.service.CategoryService;
import dev.vicaw.service.ArticleService;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import dev.vicaw.client.imageservice.ImageServiceClient;
import dev.vicaw.client.imageservice.model.input.ImageInput;
import dev.vicaw.client.imageservice.model.output.ImageInfo;
import dev.vicaw.exception.ApiException;
import dev.vicaw.exception.ExceptionHandler;
import dev.vicaw.model.article.Article;
import dev.vicaw.model.article.ArticleMapper;
import dev.vicaw.model.article.input.ArticleInput;
import dev.vicaw.model.article.input.ArticleUpdateInput;
import dev.vicaw.model.article.output.FeedOutput;
import dev.vicaw.model.article.output.ArticleFeedOutput;
import dev.vicaw.model.article.output.ArticleOutput;
import dev.vicaw.model.category.Category;

import dev.vicaw.repository.ArticleRepository;

@RequestScoped
public class ArticleServiceImpl implements ArticleService {

    @Inject
    ArticleRepository articleRepository;

    @Inject
    CategoryService categoryService;

    @Inject
    @RestClient
    ImageServiceClient imageService;

    @Inject
    ArticleMapper articleMapper;

    @Inject
    JsonWebToken token;

    @Override
    public List<Article> list(Long authorId) {
        if (authorId != null) {
            return articleRepository.list("author_id", authorId);
        }

        return articleRepository.listAll();
    }

    @Override
    public Article getById(Long id) {
        Optional<Article> article = articleRepository.findByIdOptional(id);
        if (article.isEmpty())
            throw new ApiException(404, "N??o existe nenhuma not??cia com o ID informado.");

        return article.get();
    }

    @Override
    public ArticleOutput getBySlug(String slug) {
        Optional<Article> article = articleRepository.findBySlug(slug);

        if (article.isEmpty())
            throw new ApiException(404, "N??o existe nenhuma not??cia com a slug informada.");

        return articleMapper.toArticleOutput(article.get());
    }

    @Transactional
    @Override
    public Article create(InputStream coverImage, String coverImageName, @Valid ArticleInput articleInput) {
        Article article = articleMapper.toModel(articleInput);

        // Verifica se categoria existe (Lan??a exce????o caso n??o exista.)
        categoryService.getById(article.getCategoryId());

        Slugify slugify = Slugify.builder().build();
        String slug = slugify.slugify(article.getTitulo());
        article.setSlug(slug);

        if (articleRepository.findBySlug(article.getSlug()).isPresent())
            throw new ApiException(409, "J?? existe uma not??cia com o t??tulo informado.");

        articleRepository.persist(article);

        Response res;

        try {
            res = imageService.upload(new ImageInput(coverImage, coverImageName, article.getId()));
        } catch (ProcessingException e) {
            throw new ApiException(500,
                    "N??o foi poss??vel se conectar ao servi??o de imagens. Tente novamente mais tarde.");
        }

        if (res.getStatus() != 200)
            throw new ApiException(400, "Erro ao fazer upload da imagem.");

        article.setCoverImgName(res.readEntity(ImageInfo.class).getName());

        return article;
    }

    @Transactional
    @Override
    public Article update(Long articleId, InputStream coverImage, String coverImageName,
            @Valid ArticleUpdateInput articleInput) {

        // Tamb??m verifica se a not??cia existe (Exce????o se n??o existir)
        Article original = getById(articleId);

        // Se n??o for ADMIN, verifica se o ID do usu??rio que fez a requisi????o ??
        // realmente o autor da not??cia.
        boolean isAdmin = token.getGroups().contains("ADMIN");
        if (!isAdmin) {
            if (!token.getSubject().equals(original.getAuthorId().toString()))
                throw new ApiException(400, "Voc?? n??o pode modificar a not??cia de outras pessoas.");
        }

        // Verifica se categoria informada existe (Lan??a exce????o caso n??o exista)
        if (articleInput.getCategoryId() != original.getCategoryId())
            categoryService.getById(articleInput.getCategoryId());

        // Se o t??tulo foi alterado, gera uma nova Slug.
        String slug = original.getSlug();
        if (!articleInput.getTitulo().equals(original.getTitulo())) {
            Slugify slugify = Slugify.builder().build();
            slug = slugify.slugify(articleInput.getTitulo());

            if (articleRepository.findBySlug(slug).isPresent())
                throw new ApiException(409, "J?? existe uma not??cia com o t??tulo informado.");
        }

        articleMapper.updateEntityFromInput(articleInput, original);

        original.setSlug(slug);

        // Atualiza a imagem da capa, caso uma nova tenha sido enviada.
        if (coverImage != null) {
            Response res;
            try {
                res = imageService.update(new ImageInput(coverImage, coverImageName, original.getId()));
            } catch (ProcessingException e) {
                throw new ApiException(500,
                        "N??o foi poss??vel se conectar ao servi??o de imagens. Tente novamente mais tarde.");
            }

            if (res.getStatus() != 200)
                throw new ApiException(res.getStatus(),
                        res.readEntity(ExceptionHandler.ErrorResponseBody.class).getMessage());

            original.setCoverImgName(res.readEntity(ImageInfo.class).getName());
        }

        return original;
    }

    @Transactional
    @Override
    public void delete(Long articleId) {
        Article original = getById(articleId);

        Response res;
        try {
            res = imageService.deleteAllByArticleId(articleId);
        } catch (ProcessingException e) {
            throw new ApiException(500,
                    "N??o foi poss??vel se conectar ao servi??o de imagens. Tente novamente mais tarde.");
        }

        if (res.getStatus() != 200)
            throw new ApiException(res.getStatus(),
                    res.readEntity(ExceptionHandler.ErrorResponseBody.class).getMessage());

        articleRepository.delete(original);

        return;
    }

    @Override
    public FeedOutput getFeedInfo(int pagesize, int pagenumber, String categorySlug) {

        PanacheQuery<ArticleFeedOutput> queryResult;

        if (categorySlug != null) {
            Category category = categoryService.getBySlug(categorySlug);
            queryResult = articleRepository.getAllFeedInfo(category.getId());

        } else {
            queryResult = articleRepository.getAllFeedInfo();
        }

        PanacheQuery<ArticleFeedOutput> page = queryResult.page(Page.of(pagenumber, pagesize));

        List<ArticleFeedOutput> articles = page.list();

        boolean hasMore = page.hasNextPage();

        return new FeedOutput(hasMore, articles);

    }

    @Override
    public FeedOutput searchArticle(String query, int pagesize, int pagenumber) {

        PanacheQuery<ArticleFeedOutput> queryResult = articleRepository.search(query);

        PanacheQuery<ArticleFeedOutput> page = queryResult.page(Page.of(pagenumber, pagesize));

        List<ArticleFeedOutput> articles = page.list();

        boolean hasMore = page.hasNextPage();

        return new FeedOutput(hasMore, articles);
    }

}
